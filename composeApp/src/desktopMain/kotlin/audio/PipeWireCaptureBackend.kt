package com.meetingnotes.audio

import com.meetingnotes.platform.Platform
import com.meetingnotes.platform.PlatformInfo
import java.io.File
import java.nio.file.Files

/**
 * JNI bridge object for the PipeWire native library.
 *
 * Mirrors [ScreenCaptureJniBridge] in structure: fast path via [System.loadLibrary],
 * slow path via classpath resource extraction to a temp directory.
 */
internal object PipeWireCaptureJniBridge {

    @Volatile private var loaded = false
    @Volatile private var loadFailed = false

    /**
     * Load libpipewire-jni.so. Returns true if loaded successfully; false if the
     * library is absent from classpath resources (i.e. `make` was never run).
     * Safe to call multiple times.
     */
    fun tryLoad(): Boolean {
        if (loaded) return true
        if (loadFailed) return false
        return synchronized(this) {
            if (loaded) return true
            if (loadFailed) return false
            try {
                System.loadLibrary("pipewire-jni")
                loaded = true
                true
            } catch (_: UnsatisfiedLinkError) {
                // Slow path: extract from classpath resource
                val stream = PipeWireCaptureJniBridge::class.java.getResourceAsStream("/libpipewire-jni.so")
                if (stream == null) {
                    System.err.println("[PipeWireCaptureJniBridge] libpipewire-jni.so not found in classpath. " +
                        "Build it by running: cd native/PipeWireCaptureBridge && make")
                    loadFailed = true
                    false
                } else {
                    val tmpDir = Files.createTempDirectory("agrapha-pipewire-jni").toFile()
                    val dest = File(tmpDir, "libpipewire-jni.so")
                    stream.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
                    System.load(dest.absolutePath)
                    loaded = true
                    true
                }
            }
        }
    }

    external fun nativeIsAvailable(): Boolean
    external fun nativeStartCapture(sampleRate: Int): Boolean
    external fun nativeReadBuffer(buffer: FloatArray): Int
    external fun nativeStopCapture()
}

/**
 * [SystemAudioBackend] implementation for Linux via PipeWire.
 *
 * Requires libpipewire-jni.so to be built (run `make` in native/PipeWireCaptureBridge/)
 * and PipeWire to be running on the host. If either condition fails, [isAvailable]
 * returns false and the caller falls back to a silent channel — no crash.
 *
 * @param platform injectable for testing; defaults to [PlatformInfo].
 */
class PipeWireCaptureBackend(
    private val platform: Platform = PlatformInfo,
) : SystemAudioBackend {

    override fun isAvailable(): Boolean {
        if (!platform.isLinux()) return false
        if (!PipeWireCaptureJniBridge.tryLoad()) return false
        return try {
            PipeWireCaptureJniBridge.nativeIsAvailable()
        } catch (_: Throwable) {
            false
        }
    }

    override fun startCapture(sampleRate: Int): Boolean {
        return try {
            PipeWireCaptureJniBridge.nativeStartCapture(sampleRate)
        } catch (_: Throwable) {
            false
        }
    }

    override fun readBuffer(buffer: FloatArray): Int {
        return try {
            PipeWireCaptureJniBridge.nativeReadBuffer(buffer)
        } catch (_: Throwable) {
            0
        }
    }

    override fun stopCapture() {
        try {
            PipeWireCaptureJniBridge.nativeStopCapture()
        } catch (_: Throwable) {
            // best-effort
        }
    }
}
