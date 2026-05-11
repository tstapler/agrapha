package com.meetingnotes.transcription

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.meetingnotes.domain.model.TranscriptSegment
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * [TranscriptionBackend] backed by NVIDIA Parakeet-TDT-0.6B-v3 running through ONNX Runtime.
 *
 * ## Setup
 * 1. Download the community ONNX export from
 *    `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx`
 * 2. Set [modelDir] (or pass it to [prepare]) to the directory containing:
 *    - `encoder.onnx`   — required
 *    - `tokens.txt` / `vocab.txt` — required (one SentencePiece piece per line)
 *    - `decoder.onnx`   — optional (used for RNNT-style models)
 *    - `joiner.onnx`    — optional (used for RNNT-style models)
 * 3. On first run, input/output tensor names are logged to stderr so you can verify
 *    they match what [buildInputMap] expects.  File a PR if names differ.
 *
 * ## Tensor name heuristics
 * The backend uses substring matching on standard NeMo/ONNX-ASR names.  If the export
 * uses different names the first argument tensor is tried as a fallback for the audio
 * input and the first length-shaped tensor for the length input.
 */
class ParakeetOnnxBackend(
    private val modelDir: String = "",
) : TranscriptionBackend {

    override val id = "parakeet"
    override val displayName = "Parakeet-TDT-0.6B (ONNX, experimental)"

    override val isAvailable: Boolean
        get() = try { OrtEnvironment.getEnvironment(); true } catch (_: Throwable) { false }

    override val isReady: Boolean
        get() = encoderSession != null && vocab.isNotEmpty()

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var blankId: Int = 0

    private val extractor = MelSpectrogramExtractor()

    override fun prepare(modelPath: String) {
        val dir = File(modelPath.ifBlank { modelDir })
        val encoderFile = dir.resolve("encoder.onnx")

        if (!encoderFile.exists()) {
            System.err.println(
                "[Parakeet] encoder.onnx not found in $dir — " +
                "download the model from huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx"
            )
            return
        }

        val vocabFile = sequenceOf("tokens.txt", "vocab.txt", "tokenizer.txt")
            .map { dir.resolve(it) }
            .firstOrNull { it.exists() }

        if (vocabFile == null) {
            System.err.println("[Parakeet] No vocabulary file found in $dir (tokens.txt / vocab.txt)")
            return
        }

        try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(8))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            encoderSession = env!!.createSession(encoderFile.absolutePath, opts)
            logTensorNames(encoderSession!!, "encoder")

            vocab = vocabFile.readLines()
            blankId = vocab.indexOfFirst { it == "<blank>" || it == "<blk>" || it == "" }
                .takeIf { it >= 0 } ?: 0

            System.err.println("[Parakeet] ready — vocab size ${vocab.size}, blank id $blankId")
        } catch (e: Exception) {
            System.err.println("[Parakeet] Failed to initialise ONNX session: ${e.message}")
            encoderSession = null
        }
    }

    override fun transcribe(
        audioPath: String,
        meetingId: String,
        speakerLabel: String?,
        chunkOffsetMs: Long,
        progressCallback: ((Int) -> Unit)?,
    ): List<TranscriptSegment> {
        val session = encoderSession
            ?: return listOf(errorSegment(meetingId, chunkOffsetMs,
                "[Parakeet] not ready — call prepare() with the model directory first"))
        val environment = env ?: return emptyList()

        val samples = readWavSamples(File(audioPath))
        if (samples.isEmpty()) return emptyList()

        val mel = extractor.extract(samples)
        val nFrames = mel.firstOrNull()?.size ?: 0
        if (nFrames == 0) return emptyList()

        val nMels = extractor.nMels
        val melFlat = FloatArray(nMels * nFrames) { idx ->
            val m = idx / nFrames; val t = idx % nFrames; mel[m][t]
        }

        progressCallback?.invoke(10)

        val inputs = buildInputMap(environment, session.inputNames.toList(), melFlat, nMels, nFrames)
        val result = session.run(inputs)
        inputs.values.forEach { it.close() }

        progressCallback?.invoke(80)

        val text = decodeResult(result)
        result.close()

        progressCallback?.invoke(100)

        if (text.isBlank()) return emptyList()

        val durationMs = (nFrames * hopLengthMs).toLong()
        return listOf(
            TranscriptSegment(
                id = "$meetingId-parakeet-${System.currentTimeMillis()}",
                meetingId = meetingId,
                speakerLabel = speakerLabel,
                startMs = chunkOffsetMs,
                endMs = chunkOffsetMs + durationMs,
                text = text.trim(),
            )
        )
    }

    override fun close() {
        encoderSession?.close(); encoderSession = null
        env?.close();           env = null
    }

    // ── Tensor construction ───────────────────────────────────────────────────

    private fun buildInputMap(
        env: OrtEnvironment,
        names: List<String>,
        mel: FloatArray,
        nMels: Int,
        nFrames: Int,
    ): MutableMap<String, OnnxTensor> {
        val map = mutableMapOf<String, OnnxTensor>()
        val used = mutableSetOf<String>()

        // Identify the audio tensor (first match by name heuristic, then positional fallback).
        val audioName = names.firstOrNull { n ->
            n.contains("signal") || n.contains("feature") || n.contains("mel") || n.contains("audio")
        } ?: names.firstOrNull()

        if (audioName != null) {
            map[audioName] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(mel),
                longArrayOf(1L, nMels.toLong(), nFrames.toLong()),
            )
            used += audioName
        }

        // Identify the length tensor.
        val lenName = names.firstOrNull { n ->
            n !in used && (n.contains("length") || n.contains("len"))
        } ?: names.firstOrNull { it !in used }

        if (lenName != null) {
            map[lenName] = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(nFrames.toLong())),
                longArrayOf(1L),
            )
        }

        return map
    }

    // ── Output decoding ───────────────────────────────────────────────────────

    private fun decodeResult(result: OrtSession.Result): String {
        if (result.size() == 0) return ""

        // Prefer a logit/prob/output tensor; fall back to first output.
        val outputEntry = result.firstOrNull { (name, _) ->
            name.contains("logit") || name.contains("prob") || name.contains("output") ||
            name.contains("log_prob")
        } ?: result.firstOrNull() ?: return ""

        val tensor = outputEntry.value as? OnnxTensor ?: return ""
        val shape  = tensor.info.shape
        val data   = FloatArray(tensor.floatBuffer.remaining()).also { tensor.floatBuffer.get(it) }

        return when (shape.size) {
            3    -> greedyCTCDecode(data, shape[1].toInt(), shape[2].toInt())
            4    -> greedyCTCDecode(data, (shape[1] * shape[2]).toInt(), shape[3].toInt())
            2    -> greedyCTCDecode(data, shape[0].toInt(), shape[1].toInt())
            else -> ""
        }
    }

    private fun greedyCTCDecode(logits: FloatArray, nFrames: Int, vocabSize: Int): String {
        val sb = StringBuilder()
        var prev = blankId
        for (t in 0 until nFrames) {
            var maxVal = Float.NEGATIVE_INFINITY; var maxId = 0
            val base = t * vocabSize
            for (v in 0 until vocabSize) {
                val score = logits[base + v]
                if (score > maxVal) { maxVal = score; maxId = v }
            }
            if (maxId != blankId && maxId != prev && maxId < vocab.size) {
                // SentencePiece uses ▁ (U+2581) as a word-start marker.
                sb.append(vocab[maxId].replace('▁', ' '))
            }
            prev = maxId
        }
        return sb.toString()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun logTensorNames(session: OrtSession, tag: String) {
        System.err.println("[Parakeet] $tag inputs : ${session.inputNames}")
        System.err.println("[Parakeet] $tag outputs: ${session.outputNames}")
    }

    private fun readWavSamples(file: File): FloatArray {
        return file.inputStream().use { stream ->
            stream.skip(44)  // skip the standard 44-byte PCM WAV header
            val bytes = stream.readBytes()
            FloatArray(bytes.size / 2) { i ->
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768f
            }
        }
    }

    private fun errorSegment(meetingId: String, offsetMs: Long, msg: String) = TranscriptSegment(
        id = "$meetingId-err-${System.currentTimeMillis()}",
        meetingId = meetingId,
        speakerLabel = null,
        startMs = offsetMs,
        endMs = offsetMs,
        text = msg,
    )

    companion object {
        private const val hopLengthMs = 10  // matches MelSpectrogramExtractor.hopLength / sampleRate
    }
}
