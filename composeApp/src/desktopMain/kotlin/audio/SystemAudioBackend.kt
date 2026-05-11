package com.meetingnotes.audio

/**
 * Platform-neutral abstraction for system audio capture.
 *
 * Implementations:
 *  - [ScreenCaptureBackend] — macOS ScreenCaptureKit via JNI
 *  - [PipeWireCaptureBackend] — Linux PipeWire via JNI
 *  - [NoOpSystemAudioBackend] — silent fallback for unsupported platforms or CI
 */
interface SystemAudioBackend {
    /** True if this backend is available on the current platform and can be started. */
    fun isAvailable(): Boolean

    /**
     * Begin capturing system audio at [sampleRate] Hz.
     * @return true if the stream was started successfully
     */
    fun startCapture(sampleRate: Int): Boolean

    /**
     * Read up to [buffer].size Float32 samples from the internal ring buffer.
     * @return number of samples actually written into [buffer] (may be < buffer.size)
     */
    fun readBuffer(buffer: FloatArray): Int

    /** Stop the active capture stream. */
    fun stopCapture()
}

/**
 * Safe no-op backend returned on unsupported platforms (Windows, CI without audio).
 *
 * [isAvailable] returns false so callers know not to expect real audio.
 */
class NoOpSystemAudioBackend : SystemAudioBackend {
    override fun isAvailable(): Boolean = false
    override fun startCapture(sampleRate: Int): Boolean = false
    override fun readBuffer(buffer: FloatArray): Int = 0
    override fun stopCapture() {}
}
