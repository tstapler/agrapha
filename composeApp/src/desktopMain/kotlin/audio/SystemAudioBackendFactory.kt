package com.meetingnotes.audio

import com.meetingnotes.platform.Platform
import com.meetingnotes.platform.PlatformInfo

/**
 * Selects the correct [SystemAudioBackend] for the current platform.
 *
 * - macOS  → [ScreenCaptureBackend] (ScreenCaptureKit via JNI)
 * - Linux  → [PipeWireCaptureBackend] (PipeWire via JNI; falls back gracefully if unavailable)
 * - Other  → [NoOpSystemAudioBackend] (silent; no crash)
 *
 * @param platform injectable for unit tests; production code uses the default [PlatformInfo].
 */
object SystemAudioBackendFactory {

    fun create(platform: Platform = PlatformInfo): SystemAudioBackend = when {
        platform.isMac()   -> ScreenCaptureBackend(platform)
        platform.isLinux() -> PipeWireCaptureBackend(platform)
        else               -> NoOpSystemAudioBackend()
    }
}
