package com.meetingnotes.audio

import com.meetingnotes.platform.Platform
import com.meetingnotes.platform.PlatformInfo
import java.io.File
import java.nio.file.Files

/**
 * JNI bridge object for the Rust native library (libagrapha_native.so).
 *
 * Contains both PipeWire audio capture and global hotkey JNI exports.
 * Built by `cargo build --release` in native/agrapha-native/ via the
 * Gradle `buildAgraphaNative` task.
 *
 * Load order:
 *  1. Fast path: [System.loadLibrary] (works when -Djava.library.path points at the .so)
 *  2. Slow path: extract libagrapha_native.so from classpath resources to a temp dir
 */
internal object PipeWireCaptureJniBridge {

    @Volatile private var loaded = false
    @Volatile private var loadFailed = false

    private const val LIB_RESOURCE = "/libagrapha_native.so"
    private const val LIB_NAME     = "agrapha_native"

    /**
     * Load libagrapha_native.so. Returns true if loaded; false if absent.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun tryLoad(): Boolean {
        if (loaded) return true
        if (loadFailed) return false
        return synchronized(this) {
            if (loaded) return true
            if (loadFailed) return false
            try {
                System.loadLibrary(LIB_NAME)
                loaded = true
                true
            } catch (_: UnsatisfiedLinkError) {
                val stream = PipeWireCaptureJniBridge::class.java.getResourceAsStream(LIB_RESOURCE)
                if (stream == null) {
                    System.err.println(
                        "[PipeWireCaptureJniBridge] $LIB_RESOURCE not found in classpath. " +
                        "Build it: cd native/agrapha-native && cargo build --release"
                    )
                    loadFailed = true
                    false
                } else {
                    val tmpDir = Files.createTempDirectory("agrapha-native-jni").toFile()
                    val dest = File(tmpDir, "libagrapha_native.so")
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
