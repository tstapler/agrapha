//! PipeWire system audio capture (monitor/loopback source).
//!
//! Exposes four functions called from JNI:
//!   - `is_available()` — checks Linux + PipeWire socket presence
//!   - `start(sample_rate)` — spawns the PW capture thread; returns true on success
//!   - `drain(max)` — moves up to `max` samples out of the ring buffer
//!   - `stop()` — signals the PW thread to quit and joins it
//!
//! Thread model:
//!   - JNI thread calls `start()`, `drain()`, `stop()`
//!   - A dedicated OS thread owns all PipeWire objects (they are !Send)
//!   - Audio samples flow JNI thread → ring buffer ← PW on-process callback
//!   - Stop signal: atomic bool checked by a 100 ms iterate loop in the PW thread

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use libspa::param::audio::{AudioFormat, AudioInfoRaw};
use libspa::param::ParamType;
use libspa::pod::serialize::PodSerializer;
use libspa::pod::{Object, Pod, Value};
use libspa::utils::{Direction, SpaTypes};

use pipewire::context::ContextBox;
use pipewire::main_loop::MainLoopBox;
use pipewire::properties::properties;
use pipewire::stream::{StreamBox, StreamFlags};
use pipewire::keys as pw_keys;

// ── Global capture state ──────────────────────────────────────────────────────

struct CaptureState {
    ring_buf: Arc<Mutex<VecDeque<f32>>>,
    stop: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

// Safety: all fields are Send; Mutex<Option<CaptureState>> is Sync
unsafe impl Send for CaptureState {}

static CAPTURE: Mutex<Option<CaptureState>> = Mutex::new(None);

// ── Public API ────────────────────────────────────────────────────────────────

pub fn is_available() -> bool {
    if !cfg!(target_os = "linux") {
        return false;
    }
    let runtime_dir = match std::env::var("XDG_RUNTIME_DIR") {
        Ok(d) => d,
        Err(_) => return false,
    };
    std::path::Path::new(&format!("{runtime_dir}/pipewire-0")).exists()
}

pub fn start(sample_rate: u32) -> bool {
    let mut guard = CAPTURE.lock().unwrap();
    if guard.is_some() {
        return true; // already running
    }

    let ring_buf: Arc<Mutex<VecDeque<f32>>> =
        Arc::new(Mutex::new(VecDeque::with_capacity(160_000)));
    let stop = Arc::new(AtomicBool::new(false));

    let ring_clone = ring_buf.clone();
    let stop_clone = stop.clone();

    let handle = std::thread::Builder::new()
        .name("agrapha-pipewire".to_owned())
        .spawn(move || {
            if let Err(e) = run_capture_thread(ring_clone, stop_clone, sample_rate) {
                eprintln!("[PipeWireCapture] thread error: {e}");
            }
        });

    match handle {
        Ok(t) => {
            *guard = Some(CaptureState { ring_buf, stop, thread: Some(t) });
            true
        }
        Err(e) => {
            eprintln!("[PipeWireCapture] failed to spawn thread: {e}");
            false
        }
    }
}

pub fn drain(max: usize) -> Vec<f32> {
    let guard = CAPTURE.lock().unwrap();
    let state = match guard.as_ref() {
        Some(s) => s,
        None => return vec![],
    };
    let mut ring = state.ring_buf.lock().unwrap();
    let n = ring.len().min(max);
    ring.drain(..n).collect()
}

pub fn stop() {
    let mut guard = CAPTURE.lock().unwrap();
    let state = match guard.take() {
        Some(s) => s,
        None => return,
    };
    state.stop.store(true, Ordering::SeqCst);
    if let Some(t) = state.thread {
        let _ = t.join();
    }
}

// ── PipeWire thread ───────────────────────────────────────────────────────────

fn run_capture_thread(
    ring_buf: Arc<Mutex<VecDeque<f32>>>,
    stop: Arc<AtomicBool>,
    sample_rate: u32,
) -> Result<(), Box<dyn std::error::Error>> {
    // MainLoopBox::new calls pipewire::init() internally
    let main_loop = MainLoopBox::new(None)?;
    let context = ContextBox::new(main_loop.loop_(), None)?;
    let core = context.connect(None)?;

    let props = properties! {
        *pw_keys::MEDIA_TYPE => "Audio",
        *pw_keys::MEDIA_CATEGORY => "Capture",
        *pw_keys::MEDIA_ROLE => "Music",
        // Connect to monitor port of default sink (system audio loopback)
        "stream.capture.sink" => "true",
    };

    let stream = StreamBox::new(&core, "agrapha-monitor", props)?;

    let _listener = stream
        .add_local_listener_with_user_data(ring_buf)
        .process(|stream, ring| match stream.dequeue_buffer() {
            None => {}
            Some(mut buf) => {
                let datas = buf.datas_mut();
                if let Some(data) = datas.first_mut() {
                    let byte_count = data.chunk().size() as usize;
                    if byte_count == 0 {
                        return;
                    }
                    if let Some(raw) = data.data() {
                        let n_samples = byte_count / std::mem::size_of::<f32>();
                        let samples: &[f32] = unsafe {
                            std::slice::from_raw_parts(raw.as_ptr() as *const f32, n_samples)
                        };
                        let mut r = ring.lock().unwrap();
                        // Cap ring buffer at 10 seconds to bound memory usage
                        let cap = 16_000usize * 10;
                        for &s in samples {
                            if r.len() < cap {
                                r.push_back(s);
                            }
                        }
                    }
                }
            }
        })
        .register()?;

    // Build audio/x-raw params: mono F32LE at the requested sample rate
    let param_bytes = build_audio_param_bytes(sample_rate)?;
    let pod = Pod::from_bytes(&param_bytes).ok_or("failed to build SPA audio params pod")?;
    let mut params = [pod];

    stream.connect(
        Direction::Input,
        None,
        StreamFlags::AUTOCONNECT | StreamFlags::MAP_BUFFERS,
        &mut params,
    )?;

    // Drive the event loop in 100 ms windows so the stop flag is checked promptly
    let loop_ref = main_loop.loop_();
    while !stop.load(Ordering::SeqCst) {
        loop_ref.iterate(std::time::Duration::from_millis(100));
    }

    Ok(())
}

/// Serialize a mono F32LE audio/x-raw SPA EnumFormat pod.
fn build_audio_param_bytes(sample_rate: u32) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let mut audio_info = AudioInfoRaw::new();
    audio_info.set_format(AudioFormat::F32LE);
    audio_info.set_rate(sample_rate);
    audio_info.set_channels(1);

    let obj = Object {
        type_: SpaTypes::ObjectParamFormat.as_raw(),
        id: ParamType::EnumFormat.as_raw(),
        properties: audio_info.into(),
    };

    let bytes = PodSerializer::serialize(
        std::io::Cursor::new(Vec::new()),
        &Value::Object(obj),
    )?
    .0
    .into_inner();

    Ok(bytes)
}
