#[cfg(target_os = "linux")]
mod global_shortcut;
#[cfg(target_os = "linux")]
mod pipewire_capture;

#[cfg(target_os = "macos")]
mod mac_audio_capture;
#[cfg(target_os = "macos")]
mod mac_speech_recognizer;

use jni::objects::{JClass, JFloatArray};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

// ── PipeWire capture JNI exports (Linux) ─────────────────────────────────────
// Class: com.meetingnotes.audio.PipeWireCaptureJniBridge

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeIsAvailable<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if pipewire_capture::is_available() { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeStartCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    sample_rate: jint,
) -> jboolean {
    if pipewire_capture::start(sample_rate as u32) { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeReadBuffer<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JFloatArray<'local>,
) -> jint {
    let len = match env.get_array_length(&buffer) {
        Ok(n) => n as usize,
        Err(_) => return 0,
    };
    if len == 0 { return 0; }
    let samples = pipewire_capture::drain(len);
    if samples.is_empty() { return 0; }
    match env.set_float_array_region(&buffer, 0, &samples) {
        Ok(()) => samples.len() as jint,
        Err(_) => 0,
    }
}

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeStopCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    pipewire_capture::stop();
}

// ── Global shortcut JNI exports (Linux) ──────────────────────────────────────
// Class: com.meetingnotes.hotkey.GlobalShortcutJniBridge

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_hotkey_GlobalShortcutJniBridge_nativeIsSupported<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if global_shortcut::is_supported() { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_hotkey_GlobalShortcutJniBridge_nativeRegisterAndWait<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    key_code: jint,
    modifiers: jint,
    timeout_ms: jlong,
) -> jboolean {
    let fired = global_shortcut::register_and_wait(
        key_code as u8,
        modifiers as u16,
        timeout_ms as u64,
    );
    if fired { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_hotkey_GlobalShortcutJniBridge_nativeInterrupt<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    global_shortcut::interrupt();
}

#[cfg(target_os = "linux")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_hotkey_GlobalShortcutJniBridge_nativeBackendDescription<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let desc = global_shortcut::backend_description();
    env.new_string(desc)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ── ScreenCapture JNI exports (macOS) ────────────────────────────────────────
// Class: com.meetingnotes.audio.ScreenCaptureJniBridge

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeCheckPermission<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if mac_audio_capture::check_permission() { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeRequestPermission<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if mac_audio_capture::request_permission() { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeStartCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    sample_rate: jint,
) -> jboolean {
    if mac_audio_capture::start(sample_rate as u32) { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeStopCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    mac_audio_capture::stop();
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeReadBuffer<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JFloatArray<'local>,
) -> jint {
    let len = match env.get_array_length(&buffer) {
        Ok(n) => n as usize,
        Err(_) => return 0,
    };
    if len == 0 { return 0; }
    let samples = mac_audio_capture::drain(len);
    if samples.is_empty() { return 0; }
    match env.set_float_array_region(&buffer, 0, &samples) {
        Ok(()) => samples.len() as jint,
        Err(_) => 0,
    }
}

// ── Apple Speech JNI exports (macOS) ─────────────────────────────────────────
// Class: com.meetingnotes.transcription.AppleSpeechJniBridge

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_transcription_AppleSpeechJniBridge_nativeIsAvailable<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if mac_speech_recognizer::is_available() { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_transcription_AppleSpeechJniBridge_nativeRequestAuthorization<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if mac_speech_recognizer::request_authorization() { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(target_os = "macos")]
#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_transcription_AppleSpeechJniBridge_nativeTranscribe<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    audio_path: jni::objects::JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&audio_path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match mac_speech_recognizer::transcribe(&path) {
        Ok(json) => env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(msg) => {
            let _ = env.throw_new("java/lang/RuntimeException", &msg);
            std::ptr::null_mut()
        }
    }
}
