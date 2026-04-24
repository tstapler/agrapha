package com.meetingnotes.audio

import java.io.File
import java.nio.file.Files

/**
 * JNI bridge to the AudioCaptureBridgeJNI native library.
 *
 * The native dylibs are built by running `make` in `native/AudioCaptureBridge/` and
 * are then bundled as classpath resources (via `src/desktopMain/resources/`).
 *
 * [load] first attempts `System.loadLibrary` (works when the dylibs are on
 * `java.library.path`), then falls back to extracting both dylibs from the
 * classpath into a temp directory and loading from there.  The Swift dependency
 * `libAudioCaptureBridge.dylib` must be in the same directory as the JNI dylib so
 * that the `@loader_path` rpath embedded in `AudioCaptureBridgeJNI.dylib` resolves.
 *
 * Thread safety: all native calls are internally synchronised in the .m file.
 */
object ScreenCaptureJniBridge {

    private var loaded = false

    /**
     * Load the native library. Safe to call multiple times — subsequent calls are no-ops.
     *
     * @throws UnsatisfiedLinkError if the dylib is not on `java.library.path` AND
     *   not bundled as a classpath resource (i.e. `make` was never run).
     */
    fun load() {
        if (loaded) return
        try {
            // Fast path: dylib is already on java.library.path (rare in production,
            // possible in development with an explicit -Djava.library.path= JVM arg).
            System.loadLibrary("AudioCaptureBridgeJNI")
            loaded = true
            return
        } catch (_: UnsatisfiedLinkError) {
            // Fall through to classpath extraction below.
        }

        // Slow path: extract both dylibs from classpath resources to a temp directory.
        // AudioCaptureBridgeJNI.dylib has an @loader_path rpath, so libAudioCaptureBridge.dylib
        // must sit in the same directory before the JNI dylib is loaded.
        val tmpDir = Files.createTempDirectory("meeting-notes-jni").toFile()
        extractResource("libAudioCaptureBridge.dylib", tmpDir)   // Swift dep first
        val jniLib = extractResource("AudioCaptureBridgeJNI.dylib", tmpDir)
        System.load(jniLib.absolutePath)
        loaded = true
    }

    // ── JNI Declarations ────────────────────────────────────────────────────────

    /**
     * Synchronous preflight check — returns true if screen recording permission is already
     * granted, false otherwise. Does NOT show a dialog.
     */
    external fun nativeCheckPermission(): Boolean

    /**
     * Trigger the macOS TCC permission dialog for screen recording.
     * Blocks until the user responds (up to 30 s).
     * @return true if permission was granted
     */
    external fun nativeRequestPermission(): Boolean

    /**
     * Start system audio capture at [sampleRate] Hz (16000 recommended for Whisper).
     * PCM Float32 samples are buffered internally in the native ring buffer.
     * @return true if the stream was started
     */
    external fun nativeStartCapture(sampleRate: Int): Boolean

    /** Stop the active capture stream. */
    external fun nativeStopCapture()

    /**
     * Read up to [buffer].size Float32 samples from the ring buffer.
     * @return number of samples actually read (may be < buffer.size if fewer are available)
     */
    external fun nativeReadBuffer(buffer: FloatArray): Int

    // ── Private ────────────────────────────────────────────────────────────────

    private fun extractResource(name: String, dir: File): File {
        val stream = ScreenCaptureJniBridge::class.java.getResourceAsStream("/$name")
            ?: throw UnsatisfiedLinkError(
                "Native library '$name' not found in classpath resources. " +
                    "Build it by running:  cd native/AudioCaptureBridge && make"
            )
        val dest = File(dir, name)
        stream.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
        return dest
    }
}
