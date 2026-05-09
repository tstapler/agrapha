package com.meetingnotes.audio

import com.meetingnotes.platform.PlatformInfo

/**
 * [SystemAudioBackend] adapter that delegates to [ScreenCaptureJniBridge] on macOS.
 *
 * [ScreenCaptureJniBridge] is not modified by this change; this class is a thin wrapper
 * that satisfies the [SystemAudioBackend] interface expected by [RecordingSessionManager].
 */
class ScreenCaptureBackend(
    private val platform: com.meetingnotes.platform.Platform = PlatformInfo,
) : SystemAudioBackend {

    /**
     * True only on macOS where the ScreenCaptureKit JNI library is available.
     * Attempts to load the native library; returns false if it is absent.
     */
    override fun isAvailable(): Boolean {
        if (!platform.isMac()) return false
        return try {
            ScreenCaptureJniBridge.load()
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun startCapture(sampleRate: Int): Boolean {
        return try {
            ScreenCaptureJniBridge.nativeStartCapture(sampleRate)
        } catch (_: Throwable) {
            false
        }
    }

    override fun readBuffer(buffer: FloatArray): Int {
        return try {
            ScreenCaptureJniBridge.nativeReadBuffer(buffer)
        } catch (_: Throwable) {
            0
        }
    }

    override fun stopCapture() {
        try {
            ScreenCaptureJniBridge.nativeStopCapture()
        } catch (_: Throwable) {
            // Ignore — stop is best-effort
        }
    }
}
