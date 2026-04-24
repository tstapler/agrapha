package com.meetingnotes.transcription

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Curated model list ordered by WER rank on the Open ASR Leaderboard (hf-audio/open_asr_leaderboard). */
data class WhisperModelSpec(
    val filename: String,
    val displayName: String,
    val description: String,
    val sha256: String,
    val sizeBytes: Long,
    val recommended: Boolean = false,
    /**
     * Base name of the pre-compiled CoreML encoder on the ggerganov/whisper.cpp-coreml HuggingFace
     * repo.  For example "ggml-small.en" causes the downloader to fetch
     * "ggml-small.en-encoder.mlmodelc.zip", unzip it, and store the bundle as
     * "{filename}-encoder.mlmodelc/" in the same models directory.  whisper.cpp probes for
     * "{model_path}-encoder.mlmodelc" at load time and uses CoreML when found.
     * null = no CoreML encoder available for this model.
     */
    val coremlBaseEncoder: String? = null,
    /**
     * Override the full download URL for this model.  When non-null, used instead of the default
     * "$HF_BASE/{filename}" pattern.  Useful for models hosted outside the ggerganov/whisper.cpp
     * HuggingFace repository.
     */
    val downloadUrl: String? = null,
)

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(val bytesReceived: Long, val totalBytes: Long) : ModelDownloadState()
    object Verifying : ModelDownloadState()
    data class Done(val path: String) : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

val WHISPER_MODELS = listOf(
    WhisperModelSpec(
        filename = "ggml-tiny.en.bin",
        displayName = "Tiny — English only",
        description = "77 MB · Fastest · Good for testing",
        sha256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f",
        sizeBytes = 77_704_715L,
        coremlBaseEncoder = "ggml-tiny.en",
    ),
    WhisperModelSpec(
        filename = "ggml-small.en-q5_1.bin",
        displayName = "Small Q5 — English only",
        description = "190 MB · Fast · Good for low-power or offline use",
        sha256 = "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
        sizeBytes = 190_098_681L,
        coremlBaseEncoder = "ggml-small.en",  // quantization only affects decoder; encoder is shared
    ),
    WhisperModelSpec(
        filename = "ggml-large-v3-turbo-q5_0.bin",
        displayName = "Large-v3 Turbo Q5",
        description = "574 MB · Near large-v3 accuracy · Top speed/quality tradeoff",
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        sizeBytes = 574_041_195L,
        // No pre-compiled CoreML encoder available for turbo/quantized large models
    ),
    WhisperModelSpec(
        filename = "ggml-distil-large-v3.bin",
        displayName = "distil-large-v3 (Recommended)",
        description = "1.45 GB · ~same WER as large-v3 · 2x fewer hallucinations · 6x faster",
        sha256 = "2883a11b90fb10ed592d826edeaee7d2929bf1ab985109fe9e1e7b4d2b69a298",
        sizeBytes = 1_519_521_155L,
        recommended = true,
        // Hosted in distil-whisper/distil-large-v3-ggml, not ggerganov/whisper.cpp
        downloadUrl = "https://huggingface.co/distil-whisper/distil-large-v3-ggml/resolve/main/ggml-distil-large-v3.bin",
    ),
    WhisperModelSpec(
        filename = "ggml-large-v3.bin",
        displayName = "Large-v3 (openai/whisper-large-v3)",
        description = "3.09 GB · Highest accuracy · #1 open Whisper on ASR Leaderboard",
        sha256 = "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2",
        sizeBytes = 3_095_033_483L,
        // No pre-compiled CoreML encoder available for large-v3
    ),
)

private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
private const val HF_COREML_BASE = "https://huggingface.co/ggerganov/whisper.cpp-coreml/resolve/main"
private const val EMIT_THRESHOLD = 1024 * 1024L  // emit progress every ~1 MB

class WhisperModelDownloader(private val client: HttpClient = HttpClient(CIO) {
    install(HttpRedirect) {
        checkHttpMethod = false  // HF redirects GET → GET, but be permissive
    }
    engine {
        // Downloads can be hundreds of MB — disable the CIO request timeout.
        requestTimeout = 0  // 0 = no timeout
    }
}) {


    fun isAlreadyDownloaded(spec: WhisperModelSpec, destDir: File): Boolean {
        val file = File(destDir, spec.filename)
        return file.exists() && file.length() == spec.sizeBytes
    }

    fun download(spec: WhisperModelSpec, destDir: File): Flow<ModelDownloadState> = flow {
        emit(ModelDownloadState.Downloading(0L, spec.sizeBytes))

        val destFile = File(destDir, spec.filename)

        // Already fully downloaded — re-verify SHA and short-circuit.
        if (destFile.exists() && destFile.length() == spec.sizeBytes) {
            emit(ModelDownloadState.Verifying)
            if (sha256(destFile) == spec.sha256) {
                downloadCoreMLEncoderIfNeeded(spec, destDir)
                emit(ModelDownloadState.Done(destFile.absolutePath))
                return@flow
            }
            destFile.delete()  // corrupt — fall through to re-download
        }

        destDir.mkdirs()

        val digest = MessageDigest.getInstance("SHA-256")
        var bytesReceived = 0L
        var lastEmitted = 0L

        // prepareGet().execute {} streams the response body without buffering it in memory first.
        // client.get() in Ktor 3.x reads the full body into a kotlinx.io Buffer before returning;
        // for files > Int.MAX_VALUE bytes (2.15 GB), Buffer.readByteArray() throws:
        //   "Can't create an array of size 3095033483"
        client.prepareGet(spec.downloadUrl ?: "$HF_BASE/${spec.filename}").execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            destFile.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)  // 64 KB read buffer
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buf)
                    if (read <= 0) continue
                    out.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    bytesReceived += read
                    if (bytesReceived - lastEmitted >= EMIT_THRESHOLD) {
                        emit(ModelDownloadState.Downloading(bytesReceived, spec.sizeBytes))
                        lastEmitted = bytesReceived
                    }
                }
            }
        }

        emit(ModelDownloadState.Verifying)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != spec.sha256) {
            destFile.delete()
            emit(ModelDownloadState.Error(
                "SHA-256 mismatch — file may be corrupt.\n" +
                "Expected: ${spec.sha256.take(16)}…\n" +
                "Got:      ${actual.take(16)}…"
            ))
        } else {
            downloadCoreMLEncoderIfNeeded(spec, destDir)
            emit(ModelDownloadState.Done(destFile.absolutePath))
        }
    }.catch { e ->
        emit(ModelDownloadState.Error("Download failed: ${e.message ?: e.javaClass.simpleName}"))
    }

    /**
     * Download and unzip the CoreML encoder bundle for [spec] if one is available and not yet
     * present.  Errors are silently swallowed — CoreML is optional acceleration; missing the
     * encoder simply means whisper.cpp falls back to CPU.
     *
     * The encoder is fetched from [HF_COREML_BASE] as a zip, unzipped, then renamed to
     * "{spec.filename}-encoder.mlmodelc" so that whisper.cpp finds it at load time.
     */
    private suspend fun downloadCoreMLEncoderIfNeeded(spec: WhisperModelSpec, destDir: File) {
        val encoderBase = spec.coremlBaseEncoder ?: return
        val encoderDir = File(destDir, "${spec.filename}-encoder.mlmodelc")
        if (encoderDir.exists()) return  // already present

        runCatching {
            val zipName = "$encoderBase-encoder.mlmodelc.zip"
            val zipFile = File(destDir, zipName)
            // Download the encoder zip (no progress reporting — typically 40-100 MB)
            val response = client.get("$HF_COREML_BASE/$zipName")
            val channel: ByteReadChannel = response.bodyAsChannel()
            zipFile.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buf)
                    if (read > 0) out.write(buf, 0, read)
                }
            }
            // Unzip into destDir → produces "{encoderBase}-encoder.mlmodelc/"
            unzipTo(zipFile, destDir)
            zipFile.delete()
            // Rename to match the model filename (whisper.cpp looks for {model_path}-encoder.mlmodelc)
            val unzipped = File(destDir, "$encoderBase-encoder.mlmodelc")
            if (unzipped.exists() && unzipped != encoderDir) unzipped.renameTo(encoderDir)
        }
    }

    /** Extract a zip archive into [destDir], preserving the internal directory structure. */
    private fun unzipTo(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(destDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Compute SHA-256 of an existing file (used for re-verification). */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun close() = client.close()
}
