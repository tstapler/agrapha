package com.meetingnotes.transcription

import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test for [WhisperService].
 *
 * Requires a real GGML model file on disk. The test **skips** (not @Ignore) when no
 * model is found, so CI always passes — the tests become active automatically on
 * developer machines and in CI environments where a model has been pre-downloaded.
 *
 * Model search order:
 *   1. WHISPER_MODEL_PATH environment variable
 *   2. ~/.local/share/meeting-notes/models/ggml-tiny.bin   (preferred — fastest)
 *   3. ~/.local/share/meeting-notes/models/ggml-tiny.en.bin
 *   4. Any *.bin file in that directory (falls back to whatever is available)
 *
 * Time budgets:
 *   - Model load:          20 s  (even ggml-distil-large-v3.bin loads < 10 s on SSD)
 *   - 5-second audio clip: 60 s  (ggml-tiny < 2 s; ggml-distil-large-v3 < 30 s on 8-core CPU)
 *
 * To run against a specific model:
 *   WHISPER_MODEL_PATH=/path/to/model.bin \
 *     ./gradlew :composeApp:desktopTest --tests "*.WhisperServiceIntegrationTest"
 */
class WhisperServiceIntegrationTest {

    companion object {
        private const val MODEL_LOAD_BUDGET_MS = 20_000L

        /**
         * Inference budget for 5 seconds of audio.
         *
         * Approximate CPU real-time factors (8-core, no GPU):
         *   ggml-tiny        (~75 MB)   →  ~2s     (0.4× real-time)
         *   ggml-base        (~142 MB)  →  ~5s     (1× real-time)
         *   ggml-small       (~465 MB)  →  ~20s    (4× real-time)
         *   ggml-medium      (~1.5 GB)  →  ~90s    (18× real-time)
         *   ggml-distil-large-v3 (1.5 GB) → ~100s (20× real-time)
         *
         * The budget is set per model size so that tiny/base models get a tight bound
         * while large models get enough headroom to complete on slow CI hardware.
         */
        fun transcriptionBudgetMs(modelFile: File): Long {
            val mb = modelFile.length() / (1024 * 1024)
            return when {
                mb < 200  -> 15_000L    // tiny / tiny.en
                mb < 600  -> 45_000L    // base / small
                else      -> 180_000L   // medium / large / distil-large
            }
        }

        private val MODELS_DIR =
            File(System.getProperty("user.home"), ".local/share/meeting-notes/models")

        fun findModel(): File? {
            System.getenv("WHISPER_MODEL_PATH")
                ?.let { File(it).takeIf { f -> f.isFile } }
                ?.also { return it }

            File(MODELS_DIR, "ggml-tiny.bin").takeIf { it.isFile }?.let { return it }
            File(MODELS_DIR, "ggml-tiny.en.bin").takeIf { it.isFile }?.let { return it }
            return MODELS_DIR.listFiles { f -> f.isFile && f.extension == "bin" }?.firstOrNull()
        }
    }

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var service: WhisperService
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        val found = findModel()
        Assume.assumeNotNull(
            "Skipping WhisperServiceIntegrationTest: no model in $MODELS_DIR. " +
            "Download one via the app Settings or set \$WHISPER_MODEL_PATH.",
            found,
        )
        modelFile = found!!
        service = WhisperService()
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.close()
    }

    // ── Model load ────────────────────────────────────────────────────────────

    @Test
    fun `model loads within 20 seconds`() {
        val start = System.currentTimeMillis()
        service.loadModel(modelFile.absolutePath)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(service.isLoaded, "isLoaded must be true after loadModel()")
        assertTrue(
            elapsed < MODEL_LOAD_BUDGET_MS,
            "${modelFile.name} load took ${elapsed}ms — exceeds ${MODEL_LOAD_BUDGET_MS}ms budget",
        )
        println("[integration] model load: ${elapsed}ms  model=${modelFile.name}")
    }

    // ── Transcription completion ──────────────────────────────────────────────

    @Test
    fun `transcription of 5-second silent audio completes within time budget`() {
        service.loadModel(modelFile.absolutePath)
        val budget = transcriptionBudgetMs(modelFile)

        val wav = tmp.newFile("silence-5s.wav")
        writeSilentWav(wav, durationSeconds = 5)

        val start = System.currentTimeMillis()
        val segments = service.transcribe(wav.absolutePath, "integ-silence")
        val elapsed = System.currentTimeMillis() - start

        assertNotNull(segments)
        assertTrue(
            elapsed < budget,
            "Inference took ${elapsed}ms — exceeds ${budget}ms budget on ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)",
        )
        println("[integration] silence 5s: ${segments.size} segments in ${elapsed}ms  budget=${budget}ms  model=${modelFile.name}")
    }

    @Test
    fun `transcription of 5-second tone audio completes within time budget`() {
        service.loadModel(modelFile.absolutePath)
        val budget = transcriptionBudgetMs(modelFile)

        val wav = tmp.newFile("tone-5s.wav")
        writeToneWav(wav, durationSeconds = 5, frequencyHz = 440.0)

        val start = System.currentTimeMillis()
        val segments = service.transcribe(wav.absolutePath, "integ-tone")
        val elapsed = System.currentTimeMillis() - start

        assertNotNull(segments)
        assertTrue(
            elapsed < budget,
            "Inference took ${elapsed}ms — exceeds ${budget}ms budget on ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)",
        )
        println("[integration] tone 5s: ${segments.size} segments in ${elapsed}ms  budget=${budget}ms  model=${modelFile.name}")
    }

    // ── Model reuse ───────────────────────────────────────────────────────────

    @Test
    fun `two sequential transcriptions share the loaded model without reloading`() {
        service.loadModel(modelFile.absolutePath)
        val budget = transcriptionBudgetMs(modelFile)
        val loadedPath = service.loadedModelPath

        val wav = tmp.newFile("seq.wav")
        writeSilentWav(wav, durationSeconds = 2)

        val t1 = System.currentTimeMillis()
        service.transcribe(wav.absolutePath, "seq-1")
        val elapsed1 = System.currentTimeMillis() - t1

        val t2 = System.currentTimeMillis()
        service.transcribe(wav.absolutePath, "seq-2")
        val elapsed2 = System.currentTimeMillis() - t2

        assertTrue(elapsed1 < budget, "First call ${elapsed1}ms exceeded ${budget}ms budget")
        assertTrue(elapsed2 < budget, "Second call ${elapsed2}ms exceeded ${budget}ms budget")
        assertTrue(service.loadedModelPath == loadedPath, "loadedModelPath changed — model was unexpectedly reloaded")
        println("[integration] sequential: ${elapsed1}ms / ${elapsed2}ms  budget=${budget}ms  model=${modelFile.name}")
    }

    // ── WAV helpers ───────────────────────────────────────────────────────────

    private fun writeSilentWav(file: File, durationSeconds: Int, sampleRate: Int = 16_000) =
        writeWav(file, sampleRate, FloatArray(sampleRate * durationSeconds) { 0f })

    private fun writeToneWav(file: File, durationSeconds: Int, frequencyHz: Double, sampleRate: Int = 16_000) {
        val samples = FloatArray(sampleRate * durationSeconds) { i ->
            (0.3 * Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate)).toFloat()
        }
        writeWav(file, sampleRate, samples)
    }

    private fun writeWav(file: File, sampleRate: Int, samples: FloatArray) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)              // PCM
            putShort(1)              // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)   // byte rate
            putShort(2)              // block align
            putShort(16)             // bits per sample
            put("data".toByteArray())
            putInt(dataBytes)
        }
        val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
            samples.forEach { s -> buf.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        }
        file.outputStream().buffered().use { out ->
            out.write(header.array())
            out.write(pcm.array())
        }
    }
}
