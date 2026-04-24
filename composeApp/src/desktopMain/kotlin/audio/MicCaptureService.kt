package com.meetingnotes.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Captures microphone audio via javax.sound.sampled.
 *
 * Format: 16kHz, 16-bit signed PCM, mono, little-endian.
 * Each chunk emitted by [captureFlow] is ~0.1s = 1600 samples.
 */
class MicCaptureService {

    private val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        16_000f,   // sample rate
        16,        // bit depth
        1,         // channels (mono)
        2,         // frame size (2 bytes per frame)
        16_000f,   // frame rate
        false,     // little-endian
    )

    @Volatile private var line: TargetDataLine? = null

    /**
     * Returns a [Flow] of Float32 PCM samples in the range [-1.0, 1.0].
     * Collect until the scope is cancelled or [stop] is called.
     */
    fun captureFlow(): Flow<FloatArray> = flow {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        check(AudioSystem.isLineSupported(info)) {
            "Microphone line not supported on this system"
        }
        val targetLine = AudioSystem.getLine(info) as TargetDataLine
        line = targetLine
        targetLine.open(format)
        targetLine.start()

        val buf = ByteArray(3200)  // 0.1s at 16kHz 16-bit mono
        try {
            while (currentCoroutineContext().isActive && targetLine.isOpen) {
                val n = withContext(Dispatchers.IO) { targetLine.read(buf, 0, buf.size) }
                if (n > 0) emit(pcmBytesToFloats(buf, n))
            }
        } finally {
            targetLine.stop()
            targetLine.close()
            line = null
        }
    }

    /** Stop capture — the [captureFlow] flow will complete on next iteration. */
    fun stop() {
        line?.stop()
        line?.close()
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun pcmBytesToFloats(bytes: ByteArray, count: Int): FloatArray {
        val numSamples = count / 2
        return FloatArray(numSamples) { i ->
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val pcm = (hi shl 8) or lo
            pcm / 32768f
        }
    }
}
