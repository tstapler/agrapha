package com.meetingnotes.transcription

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.io.path.absolutePathString

/**
 * Converts captured audio into whisper-jni-compatible format:
 *   16kHz sample rate, mono channel, 16-bit signed PCM (WAV header).
 *
 * Also provides stereo channel splitting for dual-channel diarization.
 */
object AudioPreprocessor {

    private const val WHISPER_SAMPLE_RATE = 16_000
    private const val DEFAULT_CHUNK_MS = 3 * 60 * 1000L  // 3-minute chunks

    /**
     * Convert [inputPath] to a 16kHz mono 16-bit PCM WAV at [outputPath].
     * Normalises amplitude and applies auto-gain for quiet recordings.
     */
    fun convertToWhisperFormat(inputPath: Path, outputPath: Path) {
        val inStream = AudioSystem.getAudioInputStream(inputPath.toFile())
        val targetFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            WHISPER_SAMPLE_RATE.toFloat(),
            16,          // bits per sample
            1,           // mono
            2,           // frame size = 2 bytes (16-bit mono)
            WHISPER_SAMPLE_RATE.toFloat(),
            false,       // little-endian
        )

        val converted = AudioSystem.getAudioInputStream(targetFormat, inStream)
        val samples = converted.readAllBytes()
        val normalised = normalise(samples)

        AudioSystem.write(
            AudioInputStream(normalised.inputStream(), targetFormat, (normalised.size / 2).toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE,
            outputPath.toFile(),
        )
    }

    /**
     * Split a stereo WAV into two mono WAV files.
     * Returns (micPath, systemPath) — channel 0 = mic, channel 1 = system audio.
     *
     * Streams the stereo file in 64 KB chunks so the full recording is never
     * loaded into memory at once (saves ~3× mono-file size compared to readAllBytes).
     */
    fun splitChannels(stereoPath: Path): Pair<Path, Path> {
        val parent = stereoPath.parent
        val baseName = stereoPath.fileName.toString().removeSuffix(".wav")
        val micPath = parent.resolve("${baseName}_mic.wav")
        val sysPath = parent.resolve("${baseName}_sys.wav")

        val inStream = AudioSystem.getAudioInputStream(stereoPath.toFile())
        val fmt = inStream.format
        require(fmt.channels == 2) { "Expected stereo WAV, got ${fmt.channels} channels" }
        require(fmt.sampleSizeInBits == 16) { "Expected 16-bit PCM" }

        val stereoFrameSize = fmt.frameSize  // 4 bytes for 16-bit stereo
        val monoFrames = inStream.frameLength  // known for file-backed streams

        micPath.toFile().outputStream().buffered().use { micOut ->
            sysPath.toFile().outputStream().buffered().use { sysOut ->
                writeMonoWavHeader(micOut, fmt.sampleRate.toInt(), monoFrames)
                writeMonoWavHeader(sysOut, fmt.sampleRate.toInt(), monoFrames)

                // Buffer sized to an exact multiple of stereoFrameSize to avoid split-frame reads
                val buf = ByteArray((65536 / stereoFrameSize) * stereoFrameSize)
                var n: Int
                while (inStream.read(buf).also { n = it } != -1) {
                    var i = 0
                    while (i + stereoFrameSize <= n) {
                        // ch0 (mic): bytes [i, i+1]; ch1 (sys): bytes [i+2, i+3]
                        micOut.write(buf[i].toInt() and 0xFF)
                        micOut.write(buf[i + 1].toInt() and 0xFF)
                        sysOut.write(buf[i + 2].toInt() and 0xFF)
                        sysOut.write(buf[i + 3].toInt() and 0xFF)
                        i += stereoFrameSize
                    }
                }
            }
        }
        inStream.close()

        return micPath to sysPath
    }

    /**
     * Split a 16kHz mono WAV into fixed-duration chunks.
     * Returns list of (chunkPath, chunkStartMs) — timestamps are the offset of each chunk
     * from the start of the original file, used to shift whisper segment timestamps.
     *
     * Streams chunks one at a time so only one chunk worth of audio (~5.7 MB for 3 min
     * at 16kHz) is in memory at once, instead of the full file.
     */
    fun splitIntoChunks(monoWavPath: Path, chunkDurationMs: Long = DEFAULT_CHUNK_MS): List<Pair<Path, Long>> {
        val inStream = AudioSystem.getAudioInputStream(monoWavPath.toFile())
        val fmt = inStream.format  // expected: 16kHz, mono, 16-bit
        val bytesPerMs = (fmt.sampleRate * fmt.frameSize / 1000).toLong()
        // Align chunk size to frame boundary
        val chunkByteCount = (bytesPerMs * chunkDurationMs).let { it - it % fmt.frameSize }
        val totalDataBytes = inStream.frameLength * fmt.frameSize
        val totalChunks = ((totalDataBytes + chunkByteCount - 1) / chunkByteCount).toInt().coerceAtLeast(1)
        val parent = monoWavPath.parent
        val baseName = monoWavPath.fileName.toString().removeSuffix(".wav")

        val readBuf = ByteArray(65536)  // 64 KB read buffer reused across chunks
        val chunks = mutableListOf<Pair<Path, Long>>()

        for (i in 0 until totalChunks) {
            val chunkStartByte = i * chunkByteCount
            val chunkEndByte = ((i + 1) * chunkByteCount).coerceAtMost(totalDataBytes)
            val thisChunkBytes = (chunkEndByte - chunkStartByte).let { it - it % fmt.frameSize }
            val thisChunkFrames = thisChunkBytes / fmt.frameSize

            val chunkPath = parent.resolve("${baseName}_chunk$i.wav")
            chunkPath.toFile().outputStream().buffered().use { out ->
                writeMonoWavHeader(out, fmt.sampleRate.toInt(), thisChunkFrames)
                var remaining = thisChunkBytes
                while (remaining > 0) {
                    val toRead = minOf(readBuf.size.toLong(), remaining).toInt()
                    val read = inStream.read(readBuf, 0, toRead)
                    if (read <= 0) break
                    out.write(readBuf, 0, read)
                    remaining -= read
                }
            }
            chunks.add(chunkPath to (i * chunkDurationMs))
        }

        inStream.close()
        return chunks
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Normalise and auto-gain 16-bit PCM byte array. */
    private fun normalise(pcm: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(pcm.size / 2) { buf.short }

        val maxAmplitude = shorts.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 1
        if (maxAmplitude == 0) return pcm  // silence

        val gain = if (maxAmplitude < 8000) 32767f / maxAmplitude else 1f

        val out = ByteBuffer.allocate(pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) {
            out.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
        }
        return out.array()
    }

    private fun writeMonoWav(pcm: ByteArray, fmt: AudioFormat, out: File) {
        AudioSystem.write(
            AudioInputStream(pcm.inputStream(), fmt, (pcm.size / 2).toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE,
            out,
        )
    }

    /**
     * Write a standard 44-byte RIFF/WAVE PCM header for a 16-bit mono file
     * with [frameLength] frames at [sampleRate] Hz.
     *
     * Used by the streaming splitChannels / splitIntoChunks paths so we can write
     * chunk files without buffering all PCM data first.
     */
    private fun writeMonoWavHeader(out: java.io.OutputStream, sampleRate: Int, frameLength: Long) {
        val dataSize = frameLength * 2  // 16-bit = 2 bytes per frame
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt((36 + dataSize).toInt())   // RIFF chunk size
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                         // fmt chunk size
        buf.putShort(1)                        // PCM
        buf.putShort(1)                        // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)             // byte rate
        buf.putShort(2)                        // block align
        buf.putShort(16)                       // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize.toInt())
        out.write(buf.array())
    }
}
