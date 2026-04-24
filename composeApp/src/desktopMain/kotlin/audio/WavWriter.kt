package com.meetingnotes.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes PCM Float32 samples to a standard 16-bit PCM WAV file.
 *
 * Usage:
 * ```
 * val writer = WavWriter(file, sampleRate = 16000, channels = 1)
 * writer.writeSamples(floatArray)
 * writer.close()  // finalises the RIFF header
 * ```
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
) {
    private val raf = RandomAccessFile(file, "rw")
    private var _totalSamples = 0L

    /** Number of samples written so far (per channel). */
    val totalSamples: Long get() = _totalSamples

    init {
        writeHeader(0)  // placeholder — updated on close
    }

    /** Append [samples] (Float32 in range -1.0..1.0) to the file. */
    fun writeSamples(samples: FloatArray) {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            buf.putShort((clamped * 32767).toInt().toShort())
        }
        raf.write(buf.array())
        _totalSamples += samples.size
    }

    /**
     * Copy raw 16-bit PCM bytes from [fromSample] to the current write position.
     * Returns an empty array if no new samples are available.
     * Must be called while holding the lock on this instance (callers use synchronized).
     */
    fun snapshotPcmFrom(fromSample: Long): ByteArray {
        val from = 44L + fromSample * 2L * channels
        val to = 44L + _totalSamples * 2L * channels
        if (to <= from) return ByteArray(0)
        val buf = ByteArray((to - from).toInt())
        raf.seek(from)
        raf.readFully(buf)
        return buf
    }

    /** Finalise the RIFF/data chunk sizes and close the file. */
    fun close() {
        val dataBytes = _totalSamples * channels * 2  // 16-bit = 2 bytes
        raf.seek(0)
        writeHeader(dataBytes)
        raf.close()
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun writeHeader(dataBytes: Long) {
        val totalSize = 36 + dataBytes
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk
        header.put("RIFF".toByteArray())
        header.putInt((totalSize and 0xFFFFFFFFL).toInt())
        header.put("WAVE".toByteArray())
        // fmt sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16)               // sub-chunk size
        header.putShort(1)              // PCM = 1
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)             // bits per sample
        // data sub-chunk
        header.put("data".toByteArray())
        header.putInt((dataBytes and 0xFFFFFFFFL).toInt())

        raf.seek(0)
        raf.write(header.array())
    }
}
