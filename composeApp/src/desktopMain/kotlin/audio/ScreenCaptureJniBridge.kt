package com.meetingnotes.audio

import java.io.File
import java.nio.file.Files

/**
 * JNI bridge to the ScreenCaptureKit audio capture functions inside libagrapha_native.dylib.
 *
 * Previously backed by two libraries (libAudioCaptureBridge.dylib + AudioCaptureBridgeJNI.dylib)
 * built from Swift + Obj-C source. Now the native implementation lives in the single Rust crate
 * at native/agrapha-native/, which is also used for Linux (PipeWire) and hotkeys.
 *
 * Build: `cargo build --release` in native/agrapha-native/ (Gradle does this automatically).
 * The resulting libagrapha_native.dylib is bundled as a classpath resource.
 *
 * Thread safety: all native calls are internally synchronised inside the Rust module.
 */
object ScreenCaptureJniBridge {

    private var loaded = false

    /**
     * Load the native library. Safe to call multiple times — subsequent calls are no-ops.
     *
     * @throws UnsatisfiedLinkError if the dylib is absent (i.e. `cargo build --release` was
     *   never run or the resource was not copied into src/desktopMain/resources/).
     */
    fun load() {
        if (loaded) return
        try {
            System.loadLibrary("agrapha_native")
            loaded = true
            return
        } catch (_: UnsatisfiedLinkError) {
            // Fall through to classpath-resource extraction.
        }

        val os = System.getProperty("os.name").lowercase()
        val libName = when {
            os.contains("mac") -> "libagrapha_native.dylib"
            os.contains("linux") -> "libagrapha_native.so"
            else -> throw UnsatisfiedLinkError("Unsupported OS for agrapha_native: $os")
        }

        val tmpDir = Files.createTempDirectory("agrapha-jni").toFile()
        val lib = extractResource(libName, tmpDir)
        System.load(lib.absolutePath)
        loaded = true
    }

    // ── JNI declarations (implemented in mac_audio_capture.rs) ────────────────

    /** Returns true if screen recording permission is already granted (no dialog). */
    external fun nativeCheckPermission(): Boolean

    /**
     * Trigger the macOS TCC permission dialog for screen recording.
     * Blocks up to ~30 s while the user responds.
     * @return true if permission was granted
     */
    external fun nativeRequestPermission(): Boolean

    /**
     * Start system audio capture at [sampleRate] Hz (16 000 recommended for Whisper).
     * PCM Float32 samples accumulate in an internal ring buffer.
     * @return true if the stream started successfully
     */
    external fun nativeStartCapture(sampleRate: Int): Boolean

    /** Stop the active capture stream and release all native resources. */
    external fun nativeStopCapture()

    /**
     * Read up to [buffer].size Float32 samples from the ring buffer.
     * @return number of samples actually read (may be < [buffer].size)
     */
    external fun nativeReadBuffer(buffer: FloatArray): Int

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun extractResource(name: String, dir: File): File {
        val stream = ScreenCaptureJniBridge::class.java.getResourceAsStream("/$name")
            ?: throw UnsatisfiedLinkError(
                "Native library '$name' not found in classpath. " +
                    "Build it: cd native/agrapha-native && cargo build --release"
            )
        val dest = File(dir, name)
        stream.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
        return dest
    }
}
