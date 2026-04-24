package com.meetingnotes.audio

import java.io.File

/**
 * Story 1 spike: captures 10 seconds of system audio and writes a WAV file.
 *
 * Run with:
 *   ./gradlew :composeApp:runSpike
 *   (or run this main() directly in IntelliJ)
 *
 * Before running:
 *   1. Build native/AudioCaptureBridge with Xcode / swift build
 *   2. Build native/AudioCaptureBridge/jni with the provided Makefile
 *   3. Ensure AudioCaptureBridgeJNI.dylib is on java.library.path
 *
 * Expected outcome:
 *   ~/Desktop/spike_capture.wav — audible recording of system audio
 */
fun main() {
    println("[Spike] Loading native library…")
    try {
        ScreenCaptureJniBridge.load()
    } catch (e: UnsatisfiedLinkError) {
        System.err.println("""
            [Spike] FAILED to load AudioCaptureBridgeJNI.
            Build the native library first:
              cd native/AudioCaptureBridge
              swift build -c release
              cd jni && make
            Then add the output dir to -Djava.library.path
        """.trimIndent())
        return
    }

    println("[Spike] Requesting screen recording permission…")
    val granted = ScreenCaptureJniBridge.nativeRequestPermission()
    if (!granted) {
        System.err.println("[Spike] Permission denied. Enable Screen Recording for this app in System Settings → Privacy.")
        return
    }

    val outFile = File(System.getProperty("user.home"), "Desktop/spike_capture.wav")
    val sampleRate = 16_000
    val durationSeconds = 10
    val writer = WavWriter(outFile, sampleRate, channels = 1)
    val readBuf = FloatArray(sampleRate / 10)  // 100ms chunks

    println("[Spike] Starting capture. Play some audio now…")
    ScreenCaptureJniBridge.nativeStartCapture(sampleRate)

    val deadline = System.currentTimeMillis() + durationSeconds * 1000L
    while (System.currentTimeMillis() < deadline) {
        val read = ScreenCaptureJniBridge.nativeReadBuffer(readBuf)
        if (read > 0) writer.writeSamples(readBuf.copyOf(read))
        Thread.sleep(50)
    }

    ScreenCaptureJniBridge.nativeStopCapture()
    writer.close()

    println("[Spike] Done! Output: ${outFile.absolutePath} (${outFile.length()} bytes)")
    println("[Spike] Open in QuickTime to verify audio quality.")
}
