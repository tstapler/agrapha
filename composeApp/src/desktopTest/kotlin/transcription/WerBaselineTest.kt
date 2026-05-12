package com.meetingnotes.transcription

import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.min
import kotlin.test.assertTrue

/**
 * Word Error Rate (WER) measurement harness for WhisperService.
 *
 * This test is **skipped** (not hard-failing) when no test data is present, so it never
 * blocks CI. Activate it by providing an audio recording and its reference transcript.
 *
 * ## Setup
 *
 * Place files at the default location:
 *   ~/.local/share/meeting-notes/wer-test/audio.wav        (16kHz mono PCM WAV)
 *   ~/.local/share/meeting-notes/wer-test/reference.txt    (verbatim ground-truth transcript)
 *
 * Or point to custom files via environment variables:
 *   WER_AUDIO_PATH=/path/to/audio.wav
 *   WER_REFERENCE_PATH=/path/to/reference.txt
 *
 * Optionally override the model:
 *   WHISPER_MODEL_PATH=/path/to/model.bin
 *
 * ## Output
 *
 * The test prints a WER report to stdout and asserts WER < 100 % (sanity-only).
 * Threshold decisions (e.g. gating a model upgrade) should be made by reading the
 * printed output — not by tightening the assertion here, which would make the harness
 * brittle across different recordings.
 *
 *   [WER] model=ggml-small.bin  WER=12.3%  (sub=5 del=2 ins=1 / ref=65 words)
 *
 * ## WER formula
 *
 *   WER = (S + D + I) / N
 *
 * where S=substitutions, D=deletions, I=insertions, N=words in the reference.
 * Computed via word-level Levenshtein distance after lowercasing and stripping punctuation.
 */
class WerBaselineTest {

    companion object {
        private val DEFAULT_DIR = File(System.getProperty("user.home"), ".local/share/meeting-notes/wer-test")
        private val DEFAULT_AUDIO = File(DEFAULT_DIR, "audio.wav")
        private val DEFAULT_REFERENCE = File(DEFAULT_DIR, "reference.txt")

        private fun resolveFile(envVar: String, default: File): File? {
            return System.getenv(envVar)?.let { File(it).takeIf(File::isFile) }
                ?: default.takeIf(File::isFile)
        }

        fun tokenize(text: String): List<String> =
            text.lowercase()
                .replace(Regex("[^a-z0-9\\s']"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }

        /** Word-level edit distance decomposed into (substitutions, deletions, insertions). */
        data class EditOps(val substitutions: Int, val deletions: Int, val insertions: Int) {
            val total: Int get() = substitutions + deletions + insertions
        }

        fun editDistance(ref: List<String>, hyp: List<String>): EditOps {
            val n = ref.size
            val m = hyp.size
            // dp[i][j] = min (sub, del, ins) ops to align ref[0..i) with hyp[0..j)
            // We store full matrix to back-trace and count op types.
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in 0..n) dp[i][0] = i
            for (j in 0..m) dp[0][j] = j
            for (i in 1..n) for (j in 1..m) {
                dp[i][j] = if (ref[i - 1] == hyp[j - 1]) dp[i - 1][j - 1]
                else min(dp[i - 1][j - 1] + 1, min(dp[i - 1][j] + 1, dp[i][j - 1] + 1))
            }

            // Back-trace to count op types
            var subs = 0; var dels = 0; var ins = 0
            var i = n; var j = m
            while (i > 0 || j > 0) {
                when {
                    i > 0 && j > 0 && ref[i - 1] == hyp[j - 1] -> { i--; j-- }
                    i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> { subs++; i--; j-- }
                    i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> { dels++; i-- }
                    else -> { ins++; j-- }
                }
            }
            return EditOps(subs, dels, ins)
        }

        fun wer(reference: String, hypothesis: String): Pair<Double, EditOps> {
            val ref = tokenize(reference)
            val hyp = tokenize(hypothesis)
            if (ref.isEmpty()) return Pair(0.0, EditOps(0, 0, 0))
            val ops = editDistance(ref, hyp)
            return Pair(ops.total.toDouble() / ref.size, ops)
        }
    }

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var audioFile: File
    private lateinit var referenceFile: File
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        val audio = resolveFile("WER_AUDIO_PATH", DEFAULT_AUDIO)
        val reference = resolveFile("WER_REFERENCE_PATH", DEFAULT_REFERENCE)
        val model = WhisperServiceIntegrationTest.findModel()

        Assume.assumeTrue(
            "Skipping WerBaselineTest: place audio.wav + reference.txt in $DEFAULT_DIR " +
            "or set \$WER_AUDIO_PATH and \$WER_REFERENCE_PATH.",
            audio != null && reference != null,
        )
        Assume.assumeNotNull(
            "Skipping WerBaselineTest: no Whisper model found. Download one via Settings or set \$WHISPER_MODEL_PATH.",
            model,
        )

        audioFile = audio!!
        referenceFile = reference!!
        modelFile = model!!
    }

    @Test
    fun `measure WER against reference transcript`() {
        val service = WhisperService()
        try {
            service.loadModel(modelFile.absolutePath)

            val segments = service.transcribe(audioFile.absolutePath, "wer-baseline")
            val hypothesis = segments.joinToString(" ") { it.text.trim() }
            val reference = referenceFile.readText()

            val (werValue, ops) = wer(reference, hypothesis)
            val refWords = tokenize(reference).size
            val werPct = "%.1f".format(werValue * 100)

            println(
                "[WER] model=${modelFile.name}  " +
                "WER=${werPct}%  " +
                "(sub=${ops.substitutions} del=${ops.deletions} ins=${ops.insertions} / ref=$refWords words)"
            )
            println("[WER] reference: ${reference.take(120)}${if (reference.length > 120) "…" else ""}")
            println("[WER] hypothesis: ${hypothesis.take(120)}${if (hypothesis.length > 120) "…" else ""}")

            // Sanity-only assertion: WER < 100% means the model produced at least some correct words.
            // Tighten this threshold in the test plan once a baseline is established.
            assertTrue(
                werValue < 1.0,
                "WER=${werPct}% — model produced no recognisable words. " +
                "Check that the audio is 16kHz mono PCM and the model loaded correctly."
            )
        } finally {
            service.close()
        }
    }
}
