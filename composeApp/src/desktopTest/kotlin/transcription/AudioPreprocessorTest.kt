package com.meetingnotes.transcription

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3-UNIT-01: convertToWhisperFormat produces 16kHz mono WAV.
 * S3-UNIT-02: splitChannels separates stereo into two mono files.
 */
class AudioPreprocessorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── S3-UNIT-01 ────────────────────────────────────────────────────────────

    @Test
    fun `convertToWhisperFormat produces 16kHz mono output`() {
        val inputPath = tmp.newFile("input.wav").toPath()
        val outputPath = Path.of(tmp.root.absolutePath, "output.wav")

        // Create a 44.1kHz stereo WAV fixture (short, ~0.1s)
        writeStereoWav(inputPath, sampleRate = 44_100, numFrames = 4_410)

        AudioPreprocessor.convertToWhisperFormat(inputPath, outputPath)

        val ais = AudioSystem.getAudioInputStream(outputPath.toFile())
        val fmt = ais.format
        ais.close()

        assertEquals(16_000f, fmt.sampleRate, "Sample rate must be 16000 Hz")
        assertEquals(1, fmt.channels, "Output must be mono")
        assertEquals(16, fmt.sampleSizeInBits, "Output must be 16-bit PCM")
    }

    @Test
    fun `convertToWhisperFormat output file is non-empty`() {
        val inputPath = tmp.newFile("input2.wav").toPath()
        val outputPath = Path.of(tmp.root.absolutePath, "output2.wav")

        writeStereoWav(inputPath, sampleRate = 44_100, numFrames = 4_410)
        AudioPreprocessor.convertToWhisperFormat(inputPath, outputPath)

        assertTrue(outputPath.toFile().length() > 44, "Output WAV must be larger than just a header")
    }

    // ── S3-UNIT-02 ────────────────────────────────────────────────────────────

    @Test
    fun `splitChannels produces two mono WAV files`() {
        val stereoPath = tmp.newFile("stereo.wav").toPath()
        writeStereoWav(stereoPath, sampleRate = 16_000, numFrames = 1_600)

        val (micPath, sysPath) = AudioPreprocessor.splitChannels(stereoPath)

        assertTrue(micPath.toFile().exists(), "Mic channel file must be created")
        assertTrue(sysPath.toFile().exists(), "System channel file must be created")

        val micFmt = AudioSystem.getAudioInputStream(micPath.toFile()).also { it.close() }.format
        val sysFmt = AudioSystem.getAudioInputStream(sysPath.toFile()).also { it.close() }.format

        assertEquals(1, micFmt.channels, "Mic output must be mono")
        assertEquals(1, sysFmt.channels, "System output must be mono")
    }

    @Test
    fun `splitChannels output files are named with _mic and _sys suffixes`() {
        val stereoPath = tmp.newFile("meeting.wav").toPath()
        writeStereoWav(stereoPath, sampleRate = 16_000, numFrames = 1_600)

        val (micPath, sysPath) = AudioPreprocessor.splitChannels(stereoPath)

        assertTrue(micPath.fileName.toString().endsWith("_mic.wav"))
        assertTrue(sysPath.fileName.toString().endsWith("_sys.wav"))
    }

    // ── S3-UNIT-03: splitIntoChunks ───────────────────────────────────────────

    @Test
    fun `splitIntoChunks returns single chunk for short audio`() {
        val monoPath = tmp.newFile("short16k.wav").toPath()
        writeMono16kWav(monoPath, numFrames = 16_000)  // 1 second at 16kHz

        val chunks = AudioPreprocessor.splitIntoChunks(monoPath, chunkDurationMs = 180_000)  // 3 min chunks

        assertEquals(1, chunks.size, "1-second audio should produce exactly 1 chunk")
        assertEquals(0L, chunks[0].second, "First chunk offset must be 0ms")
    }

    @Test
    fun `splitIntoChunks produces correct offsets for multi-chunk audio`() {
        val monoPath = tmp.newFile("long16k.wav").toPath()
        writeMono16kWav(monoPath, numFrames = 160_000)  // 10 seconds at 16kHz

        val chunks = AudioPreprocessor.splitIntoChunks(monoPath, chunkDurationMs = 3_000)  // 3-second chunks

        assertTrue(chunks.size >= 3, "10-second audio at 3s chunks should produce >= 3 chunks")
        assertEquals(0L, chunks[0].second, "First chunk offset must be 0ms")
        assertEquals(3_000L, chunks[1].second, "Second chunk offset must be 3000ms")
        assertEquals(6_000L, chunks[2].second, "Third chunk offset must be 6000ms")
    }

    @Test
    fun `splitIntoChunks chunk files are written and non-empty`() {
        val monoPath = tmp.newFile("chunked16k.wav").toPath()
        writeMono16kWav(monoPath, numFrames = 32_000)  // 2 seconds at 16kHz

        val chunks = AudioPreprocessor.splitIntoChunks(monoPath, chunkDurationMs = 1_000)

        assertTrue(chunks.size >= 2)
        chunks.forEach { (path, _) ->
            assertTrue(path.toFile().exists(), "Chunk file must be created: $path")
            assertTrue(path.toFile().length() > 44, "Chunk file must be larger than just a WAV header")
        }
    }

    @Test
    fun `splitChannels preserves distinct channel data`() {
        val stereoPath = tmp.newFile("channels.wav").toPath()
        // Channel 0 (mic) = silent; channel 1 (sys) = loud 1kHz tone
        writeKnownStereoWav(stereoPath)

        val (micPath, sysPath) = AudioPreprocessor.splitChannels(stereoPath)

        val micEnergy = rmsEnergy(micPath)
        val sysEnergy = rmsEnergy(sysPath)

        // Sys channel must have substantially more energy than silent mic channel
        assertTrue(sysEnergy > micEnergy * 10, "System channel energy ($sysEnergy) must be >> mic ($micEnergy)")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Write a 16kHz mono 16-bit PCM WAV with a 440Hz sine — matches the format expected by splitIntoChunks. */
    private fun writeMono16kWav(path: Path, numFrames: Int) {
        val sampleRate = 16_000
        val fmt = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate.toFloat(), 16, 1, 2, sampleRate.toFloat(), false)
        val buf = ByteBuffer.allocate(numFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numFrames) {
            val sample = (Short.MAX_VALUE * Math.sin(2 * Math.PI * 440 * i / sampleRate)).toInt().toShort()
            buf.putShort(sample)
        }
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(buf.array()), fmt, numFrames.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE,
            path.toFile(),
        )
    }

    /** Write a stereo 16-bit PCM WAV with a 440Hz sine on both channels. */
    private fun writeStereoWav(path: Path, sampleRate: Int, numFrames: Int) {
        val fmt = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate.toFloat(), 16, 2, 4, sampleRate.toFloat(), false)
        val buf = ByteBuffer.allocate(numFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numFrames) {
            val sample = (Short.MAX_VALUE * Math.sin(2 * Math.PI * 440 * i / sampleRate)).toInt().toShort()
            buf.putShort(sample)
            buf.putShort(sample)
        }
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(buf.array()), fmt, numFrames.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE,
            path.toFile(),
        )
    }

    /** Write a stereo WAV where ch0 is silence and ch1 is a loud 1kHz tone. */
    private fun writeKnownStereoWav(path: Path) {
        val sampleRate = 16_000
        val numFrames = 1_600
        val fmt = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate.toFloat(), 16, 2, 4, sampleRate.toFloat(), false)
        val buf = ByteBuffer.allocate(numFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numFrames) {
            val loudSample = (Short.MAX_VALUE * 0.9 * Math.sin(2 * Math.PI * 1000 * i / sampleRate)).toInt().toShort()
            buf.putShort(0)          // ch0 = silence
            buf.putShort(loudSample) // ch1 = loud
        }
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(buf.array()), fmt, numFrames.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE,
            path.toFile(),
        )
    }

    /** RMS energy of 16-bit PCM samples in a WAV file (skips 44-byte header). */
    private fun rmsEnergy(wavPath: Path): Double {
        val bytes = wavPath.toFile().readBytes()
        val headerSize = 44
        if (bytes.size <= headerSize) return 0.0
        val buf = ByteBuffer.wrap(bytes, headerSize, bytes.size - headerSize).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0
        while (buf.remaining() >= 2) {
            val s = buf.short.toDouble()
            sum += s * s
            count++
        }
        return if (count == 0) 0.0 else Math.sqrt(sum / count)
    }
}
