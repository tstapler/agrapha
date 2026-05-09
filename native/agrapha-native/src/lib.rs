mod global_shortcut;
mod pipewire_capture;

use jni::objects::{JClass, JFloatArray};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

// ── PipeWire capture JNI exports ─────────────────────────────────────────────
// Class: com.meetingnotes.audio.PipeWireCaptureJniBridge

#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeIsAvailable<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if pipewire_capture::is_available() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeStartCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    sample_rate: jint,
) -> jboolean {
    if pipewire_capture::start(sample_rate as u32) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

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
    if len == 0 {
        return 0;
    }
    let samples = pipewire_capture::drain(len);
    if samples.is_empty() {
        return 0;
    }
    match env.set_float_array_region(&buffer, 0, &samples) {
        Ok(()) => samples.len() as jint,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeStopCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    pipewire_capture::stop();
}

// ── Global shortcut JNI exports ───────────────────────────────────────────────
// Class: com.meetingnotes.hotkey.GlobalShortcutJniBridge

#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_hotkey_GlobalShortcutJniBridge_nativeIsSupported<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if global_shortcut::is_supported() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Registers the hotkey and blocks until it fires or the timeout elapses.
/// Returns JNI_TRUE if the hotkey fired, JNI_FALSE on timeout or interrupt.
/// keyCode: X11 keycode (e.g. 65 = Space); modifiers: X11 ModMask (e.g. 0x40 = Mod4/Super).
/// On Wayland, keyCode/modifiers are advisory — the compositor assigns the actual key.
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

#[no_mangle]
pub extern "system" fn Java_com_meetingnotes_hotkey_GlobalShortcutJniBridge_nativeInterrupt<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    global_shortcut::interrupt();
}

/// Returns a human-readable description of the active hotkey backend, or an error message.
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
