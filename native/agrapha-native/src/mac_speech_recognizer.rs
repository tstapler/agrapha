//! macOS speech recognition via SFSpeechRecognizer (Speech.framework).
//!
//! Uses raw Obj-C messaging (msg_send! / msg_send_id!) rather than generated objc2-speech
//! bindings so no additional crate dependency is required. All class lookups are done at
//! runtime: if the Speech framework is unavailable, `is_available()` returns false and
//! JNI callers fall back gracefully.
//!
//! SFSpeechRecognizer notes:
//! - Available on macOS 10.15+. On Apple Silicon it uses the Neural Engine (very fast).
//! - Requires one-time user permission (NSMicrophoneUsageDescription / NSSpeechRecognitionUsageDescription).
//! - Maximum reliable recognition duration is ~60 seconds per request.
//! - `shouldReportPartialResults = false` means the handler fires once with the final result.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::{AnyClass, AnyObject};
use objc2::{msg_send, msg_send_id};
use objc2_foundation::{NSError, NSString};

// Link the Speech framework so ObjC classes are registered at dylib load time.
#[link(name = "Speech", kind = "framework")]
extern "C" {}

// SFSpeechRecognizerAuthorizationStatus enum (NSInteger):
//   .notDetermined = 0, .denied = 1, .restricted = 2, .authorized = 3
const SF_AUTHORIZED: i64 = 3;

// ── Public API ────────────────────────────────────────────────────────────────

/// Returns true if SFSpeechRecognizer is available on this OS version.
pub fn is_available() -> bool {
    AnyClass::get(c"SFSpeechRecognizer").is_some()
}

/// Request speech recognition authorization from the user.
/// Blocks until the permission dialog is dismissed (or resolves immediately if
/// a decision was already made). Returns true only if status == .authorized.
pub fn request_authorization() -> bool {
    let Some(cls) = AnyClass::get(c"SFSpeechRecognizer") else {
        return false;
    };

    let result: Arc<(Mutex<Option<bool>>, Condvar)> =
        Arc::new((Mutex::new(None), Condvar::new()));
    let result2 = result.clone();

    // void (^)(SFSpeechRecognizerAuthorizationStatus) — status is NSInteger
    let handler = RcBlock::new(move |status: i64| {
        let (lock, cvar) = &*result2;
        *lock.lock().unwrap() = Some(status == SF_AUTHORIZED);
        cvar.notify_one();
    });

    unsafe {
        let _: () = msg_send![cls, requestAuthorization: &*handler];
    }

    let (lock, cvar) = &*result;
    let mut guard = lock.lock().unwrap();
    while guard.is_none() {
        guard = cvar.wait(guard).unwrap();
    }
    guard.unwrap_or(false)
}

/// Transcribe an audio file using SFSpeechRecognizer.
///
/// Returns a JSON array of segments:
/// ```json
/// [{"text":"Hello world","start_ms":0,"end_ms":1200}]
/// ```
///
/// Errors are returned as `Err(String)` — the caller should convert to a JNI exception.
pub fn transcribe(path: &str) -> Result<String, String> {
    let recognizer_cls = AnyClass::get(c"SFSpeechRecognizer")
        .ok_or_else(|| "Speech framework not available on this OS".to_string())?;
    let request_cls = AnyClass::get(c"SFSpeechURLRecognitionRequest")
        .ok_or_else(|| "SFSpeechURLRecognitionRequest class not found".to_string())?;
    let url_cls = AnyClass::get(c"NSURL")
        .ok_or_else(|| "NSURL class not found".to_string())?;

    let result: Arc<(Mutex<Option<Result<String, String>>>, Condvar)> =
        Arc::new((Mutex::new(None), Condvar::new()));
    let result2 = result.clone();

    // Guard against duplicate final-result callbacks (defensive; shouldn't happen with
    // shouldReportPartialResults = false, but the SDK contract doesn't guarantee it).
    let handled = Arc::new(AtomicBool::new(false));
    let handled2 = handled.clone();

    // void (^)(SFSpeechRecognitionResult * _Nullable, NSError * _Nullable)
    let handler = RcBlock::new(
        move |recognition_result: *mut AnyObject, error: *mut NSError| {
            if handled2.swap(true, Ordering::SeqCst) {
                return;
            }

            let outcome: Result<String, String> = if !error.is_null() {
                let msg = unsafe {
                    let desc: Retained<NSString> = msg_send_id![error, localizedDescription];
                    desc.to_string()
                };
                Err(format!("SFSpeechRecognizer error: {msg}"))
            } else if recognition_result.is_null() {
                Err("SFSpeechRecognizer returned null result with no error".to_string())
            } else {
                let is_final: bool = unsafe { msg_send![recognition_result, isFinal] };
                if !is_final {
                    // Partial result — not expected with shouldReportPartialResults=false
                    return;
                }
                let json = unsafe { segments_to_json(recognition_result) };
                Ok(json)
            };

            let (lock, cvar) = &*result2;
            *lock.lock().unwrap() = Some(outcome);
            cvar.notify_one();
        },
    );

    unsafe {
        // Build file URL from path
        let path_ns = NSString::from_str(path);
        let url: Retained<AnyObject> =
            msg_send_id![url_cls, fileURLWithPath: &*path_ns];

        // Create recognition request for the file URL
        let req_alloc: Retained<AnyObject> = msg_send_id![request_cls, alloc];
        let req: Retained<AnyObject> =
            msg_send_id![&*req_alloc, initWithURL: url.as_ptr()];
        let _: () = msg_send![&*req, setShouldReportPartialResults: false];

        // Create recognizer (uses system locale by default)
        let recognizer: Retained<AnyObject> = msg_send_id![recognizer_cls, new];

        // Start the task — handler fires on an internal dispatch queue
        let _task: Retained<AnyObject> = msg_send_id![
            &*recognizer,
            recognitionTaskWithRequest: req.as_ptr()
            resultHandler: &*handler
        ];
    }

    // Block the calling thread until the handler fires
    let (lock, cvar) = &*result;
    let mut guard = lock.lock().unwrap();
    while guard.is_none() {
        guard = cvar.wait(guard).unwrap();
    }
    guard.take().unwrap()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Extract word-level timing from `SFSpeechRecognitionResult` and serialise to JSON.
///
/// Uses `bestTranscription.segments` (each is a `SFTranscriptionSegment`) for timing.
/// Falls back to the raw formatted string as a single 0–5s segment if segments are absent.
unsafe fn segments_to_json(result: *mut AnyObject) -> String {
    let transcription: *mut AnyObject = msg_send![result, bestTranscription];
    if transcription.is_null() {
        return "[]".to_string();
    }

    let segments: *mut AnyObject = msg_send![transcription, segments];
    if segments.is_null() {
        // Fallback: return the whole utterance as one synthetic segment
        let formatted: Retained<NSString> = msg_send_id![transcription, formattedString];
        let text = json_escape(formatted.to_string());
        return format!(r#"[{{"text":"{text}","start_ms":0,"end_ms":5000}}]"#);
    }

    let count: usize = msg_send![segments, count];
    let mut parts = Vec::with_capacity(count);

    for i in 0..count {
        let seg: *mut AnyObject = msg_send![segments, objectAtIndex: i];
        if seg.is_null() {
            continue;
        }

        let substring: Retained<NSString> = msg_send_id![seg, substring];
        let timestamp: f64 = msg_send![seg, timestamp]; // seconds from start
        let duration: f64 = msg_send![seg, duration];   // seconds

        let text = json_escape(substring.to_string());
        let start_ms = (timestamp * 1000.0) as i64;
        let end_ms = ((timestamp + duration) * 1000.0) as i64;

        parts.push(format!(
            r#"{{"text":"{text}","start_ms":{start_ms},"end_ms":{end_ms}}}"#
        ));
    }

    format!("[{}]", parts.join(","))
}

fn json_escape(s: String) -> String {
    s.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
        .replace('\r', "\\r")
        .replace('\t', "\\t")
}
