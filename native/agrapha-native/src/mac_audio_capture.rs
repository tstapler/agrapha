//! macOS system audio capture using ScreenCaptureKit via objc2.
//!
//! Replaces the Swift + Obj-C JNI shim in native/AudioCaptureBridge/.
//! Exposes the same five functions called from JNI:
//!   - `check_permission()` — silent TCC preflight (no dialog)
//!   - `request_permission()` — trigger TCC dialog; blocks until user responds
//!   - `start(sample_rate)` — begin capture; returns false if permission missing
//!   - `drain(max)` — move up to `max` Float32 samples out of the ring buffer
//!   - `stop()` — tear down the stream
//!
//! Synchronisation model:
//!   - Obj-C completion handlers are converted to blocking calls via Condvar.
//!   - Audio data flows: SCStream callback → Mutex<VecDeque<f32>> ← JNI drain().
//!   - The SCStream and delegate are kept alive inside CaptureState for the
//!     duration of the session; dropping CaptureState stops the stream.

use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex};
use std::ffi::c_void;

use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::ProtocolObject;
use objc2::{define_class, msg_send, AnyThread, DeclaredClass};
use objc2_foundation::{NSArray, NSError, NSObject};
use objc2_screen_capture_kit::{
    SCContentFilter, SCDisplay, SCShareableContent, SCStream, SCStreamConfiguration,
    SCStreamDelegate, SCStreamOutput, SCStreamOutputType,
};
use objc2_core_media::CMSampleBuffer;

// ── CoreGraphics C API ────────────────────────────────────────────────────────

#[link(name = "CoreGraphics", kind = "framework")]
unsafe extern "C" {
    fn CGPreflightScreenCaptureAccess() -> bool;
}

// ── Ring buffer state ─────────────────────────────────────────────────────────

struct CaptureState {
    ring_buf: Arc<Mutex<VecDeque<f32>>>,
    // Kept alive so the stream and its ObjC delegate are not deallocated
    _stream: Retained<SCStream>,
    _delegate: Retained<AudioDelegate>,
}

unsafe impl Send for CaptureState {}

static CAPTURE: Mutex<Option<CaptureState>> = Mutex::new(None);

// ── Public API ────────────────────────────────────────────────────────────────

/// Silent TCC preflight — no dialog shown.
pub fn check_permission() -> bool {
    unsafe { CGPreflightScreenCaptureAccess() }
}

/// Enumerate shareable content (triggers TCC dialog on first call).
/// Returns true if permission is now granted.
pub fn request_permission() -> bool {
    let result = Arc::new((Mutex::new(Option::<bool>::None), Condvar::new()));
    let result2 = result.clone();

    let completion = RcBlock::new(move |_content: *mut c_void, err: *mut NSError| {
        let granted = err.is_null();
        let (lock, cvar) = &*result2;
        *lock.lock().unwrap() = Some(granted);
        cvar.notify_one();
    });

    unsafe {
        SCShareableContent::getShareableContentExcludingDesktopWindows_onScreenWindowsOnly_completionHandler(
            false,
            false,
            &completion,
        );
    }

    let (lock, cvar) = &*result;
    let mut guard = lock.lock().unwrap();
    while guard.is_none() {
        guard = cvar.wait(guard).unwrap();
    }
    guard.unwrap_or(false)
}

pub fn start(sample_rate: u32) -> bool {
    let mut guard = CAPTURE.lock().unwrap();
    if guard.is_some() {
        return true;
    }

    // ── Enumerate displays (blocking) ─────────────────────────────────────────
    let displays_result = Arc::new((Mutex::new(Option::<Vec<Retained<SCDisplay>>>::None), Condvar::new()));
    let displays_result2 = displays_result.clone();

    let enum_completion = RcBlock::new(move |content: *mut SCShareableContent, err: *mut NSError| {
        let value = if err.is_null() && !content.is_null() {
            unsafe {
                let content_ref = &*content;
                let displays = content_ref.displays();
                // Collect Retained references to each display
                let mut v = Vec::new();
                for i in 0..displays.count() {
                    let d = displays.objectAtIndex(i);
                    v.push(d);
                }
                Some(v)
            }
        } else {
            eprintln!("[MacAudioCapture] enumeration failed");
            Some(vec![])
        };
        let (lock, cvar) = &*displays_result2;
        *lock.lock().unwrap() = value;
        cvar.notify_one();
    });

    unsafe {
        SCShareableContent::getShareableContentExcludingDesktopWindows_onScreenWindowsOnly_completionHandler(
            false,
            false,
            &enum_completion,
        );
    }

    let displays = {
        let (lock, cvar) = &*displays_result;
        let mut g = lock.lock().unwrap();
        while g.is_none() { g = cvar.wait(g).unwrap(); }
        g.take().unwrap_or_default()
    };

    let display = match displays.into_iter().next() {
        Some(d) => d,
        None => {
            eprintln!("[MacAudioCapture] no display found — screen recording permission likely denied");
            return false;
        }
    };

    // ── Build stream configuration ────────────────────────────────────────────
    let config = unsafe {
        let c = SCStreamConfiguration::new();
        c.setCapturesAudio(true);
        c.setSampleRate(sample_rate as f64);
        c.setChannelCount(1);
        c.setExcludesCurrentProcessAudio(false);
        c
    };

    // ── Content filter: all audio from the primary display ───────────────────
    let filter = unsafe {
        SCContentFilter::initWithDisplay_excludingApplications_exceptingWindows(
            SCContentFilter::alloc(),
            &*display,
            &NSArray::new(),
            &NSArray::new(),
        )
    };

    // ── Create delegate with ring buffer ──────────────────────────────────────
    let ring_buf: Arc<Mutex<VecDeque<f32>>> =
        Arc::new(Mutex::new(VecDeque::with_capacity(160_000)));

    let delegate = AudioDelegate::new(ring_buf.clone());

    // ── Create stream ─────────────────────────────────────────────────────────
    let stream = unsafe {
        SCStream::initWithFilter_configuration_delegate(
            SCStream::alloc(),
            &filter,
            &config,
            Some(ProtocolObject::from_ref(&*delegate)),
        )
    };

    // ── Register audio output ─────────────────────────────────────────────────
    let add_result = unsafe {
        stream.addStreamOutput_type_sampleHandlerQueue_error(
            ProtocolObject::from_ref(&*delegate),
            SCStreamOutputType::Audio,
            None,
        )
    };

    if let Err(e) = add_result {
        eprintln!("[MacAudioCapture] addStreamOutput failed: {:?}", e);
        return false;
    }

    // ── Start capture (blocking on completion handler) ────────────────────────
    let start_result: Arc<(Mutex<Option<bool>>, Condvar)> =
        Arc::new((Mutex::new(None), Condvar::new()));
    let start_result2 = start_result.clone();

    let start_block = RcBlock::new(move |err: *mut NSError| {
        let ok = err.is_null();
        if !ok {
            eprintln!("[MacAudioCapture] startCapture failed");
        }
        let (lock, cvar) = &*start_result2;
        *lock.lock().unwrap() = Some(ok);
        cvar.notify_one();
    });

    unsafe { stream.startCaptureWithCompletionHandler(Some(&start_block)) };

    let started = {
        let (lock, cvar) = &*start_result;
        let mut g = lock.lock().unwrap();
        while g.is_none() { g = cvar.wait(g).unwrap(); }
        g.unwrap_or(false)
    };

    if started {
        *guard = Some(CaptureState { ring_buf, _stream: stream, _delegate: delegate });
        eprintln!("[MacAudioCapture] capture started at {} Hz", sample_rate);
    }

    started
}

pub fn drain(max: usize) -> Vec<f32> {
    let guard = CAPTURE.lock().unwrap();
    match guard.as_ref() {
        None => vec![],
        Some(state) => {
            let mut ring = state.ring_buf.lock().unwrap();
            let n = ring.len().min(max);
            ring.drain(..n).collect()
        }
    }
}

pub fn stop() {
    let mut guard = CAPTURE.lock().unwrap();
    let state = match guard.take() {
        Some(s) => s,
        None => return,
    };

    let done: Arc<(Mutex<bool>, Condvar)> = Arc::new((Mutex::new(false), Condvar::new()));
    let done2 = done.clone();

    let stop_block = RcBlock::new(move |err: *mut NSError| {
        if !err.is_null() {
            eprintln!("[MacAudioCapture] stopCapture error");
        }
        let (lock, cvar) = &*done2;
        *lock.lock().unwrap() = true;
        cvar.notify_one();
    });

    unsafe { state._stream.stopCaptureWithCompletionHandler(Some(&stop_block)) };

    let (lock, cvar) = &*done;
    let mut g = lock.lock().unwrap();
    while !*g { g = cvar.wait(g).unwrap(); }
    eprintln!("[MacAudioCapture] capture stopped");
    // state drops here → releases SCStream and delegate
}

// ── Obj-C delegate class ──────────────────────────────────────────────────────
// Implements SCStreamOutput (audio data) and SCStreamDelegate (error events).

/// Ivars: the ring buffer shared with the JNI drain() caller.
struct AudioDelegateIvars {
    ring: Arc<Mutex<VecDeque<f32>>>,
}

define_class!(
    #[unsafe(super(NSObject))]
    #[thread_kind = AnyThread]
    #[name = "AgraphaAudioDelegate"]
    #[ivars = AudioDelegateIvars]
    struct AudioDelegate;

    unsafe impl SCStreamOutput for AudioDelegate {
        #[unsafe(method(stream:didOutputSampleBuffer:ofType:))]
        fn did_output_sample_buffer(
            &self,
            _stream: &SCStream,
            sample_buffer: &CMSampleBuffer,
            of_type: SCStreamOutputType,
        ) {
            if of_type != SCStreamOutputType::Audio {
                return;
            }
            let ring = &self.ivars().ring;
            push_samples_from_buffer(sample_buffer, ring);
        }
    }

    unsafe impl SCStreamDelegate for AudioDelegate {
        #[unsafe(method(stream:didStopWithError:))]
        fn stream_did_stop(&self, _stream: &SCStream, error: &NSError) {
            eprintln!("[MacAudioCapture] stream stopped: {:?}", error.localizedDescription());
        }
    }
);

impl AudioDelegate {
    fn new(ring: Arc<Mutex<VecDeque<f32>>>) -> Retained<Self> {
        let this = Self::alloc();
        let this = this.set_ivars(AudioDelegateIvars { ring });
        unsafe { msg_send![super(this), init] }
    }
}

// ── CMSampleBuffer → Float32 extraction ──────────────────────────────────────

/// Extract interleaved Float32 PCM from an SCStream audio buffer and push into `ring`.
/// ScreenCaptureKit delivers mono F32LE when `channelCount = 1`.
fn push_samples_from_buffer(
    sample_buffer: &CMSampleBuffer,
    ring: &Mutex<VecDeque<f32>>,
) {
    // Use C FFI to get the block buffer from the sample buffer.
    let block_buf = unsafe {
        CMSampleBufferGetDataBuffer(sample_buffer as *const CMSampleBuffer as *const c_void)
    };
    if block_buf.is_null() {
        return;
    }

    // CMBlockBufferGetDataPointer via C FFI gives us a pointer to the raw bytes.
    let mut data_ptr: *mut i8 = std::ptr::null_mut();
    let mut len_at_offset: usize = 0;
    let mut total_len: usize = 0;

    let status = unsafe {
        CMBlockBufferGetDataPointer(
            block_buf,
            0,
            &mut len_at_offset,
            &mut total_len,
            &mut data_ptr,
        )
    };

    if status != 0 || data_ptr.is_null() || total_len == 0 {
        return;
    }

    let n_samples = total_len / std::mem::size_of::<f32>();
    let samples: &[f32] = unsafe {
        std::slice::from_raw_parts(data_ptr as *const f32, n_samples)
    };

    let mut r = ring.lock().unwrap();
    // Cap at 10 seconds at 48 kHz (worst case) to bound memory
    let cap = 48_000usize * 10;
    for &s in samples {
        if r.len() < cap {
            r.push_back(s);
        }
    }
}

// CoreMedia C functions for sample/block buffer data access (not yet in objc2-core-media)
#[link(name = "CoreMedia", kind = "framework")]
unsafe extern "C" {
    fn CMSampleBufferGetDataBuffer(sbuf: *const c_void) -> *mut c_void;
    fn CMBlockBufferGetDataPointer(
        the_buffer: *mut c_void,
        offset: usize,
        length_at_offset_out: *mut usize,
        total_length_out: *mut usize,
        data_pointer_out: *mut *mut i8,
    ) -> i32;
}
