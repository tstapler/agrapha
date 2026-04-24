package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TranscriptCorrectionService(private val httpClient: HttpClient = defaultClient()) {

    suspend fun correct(
        segments: List<TranscriptSegment>,
        model: String,
        baseUrl: String,
    ): List<TranscriptSegment> {
        if (segments.isEmpty()) return segments

        val result = mutableListOf<TranscriptSegment>()

        for (batchStart in segments.indices step BATCH_SIZE) {
            val batchEnd = minOf(batchStart + BATCH_SIZE, segments.size)
            val batch = segments.subList(batchStart, batchEnd)
            result.addAll(correctBatch(batch, model, baseUrl))
        }

        return result
    }

    private suspend fun correctBatch(
        batch: List<TranscriptSegment>,
        model: String,
        baseUrl: String,
    ): List<TranscriptSegment> {
        val request = CorrectionRequest(
            model = model,
            prompt = buildPrompt(batch),
            system = SYSTEM_PROMPT,
            stream = false,
        )

        val response = runCatching {
            httpClient.post("$baseUrl/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<CorrectionResponse>()
        }.getOrElse {
            return batch  // fail-safe: return original batch on HTTP error
        }

        if (!response.error.isNullOrBlank()) {
            return batch  // fail-safe: return original batch on Ollama error
        }

        return parseResponse(response.response, batch)
    }

    companion object {
        internal const val BATCH_SIZE = 20

        internal val SYSTEM_PROMPT =
            "You are an ASR transcript corrector. Fix ONLY clear speech recognition errors such as misheard words, " +
            "phonetically similar substitutions, and garbled technical terms. Do NOT rephrase, summarize, add " +
            "punctuation, or change meaning. If unsure, leave the segment unchanged. Return one corrected line per " +
            "input line using the exact format INDEX|CORRECTED_TEXT."

        internal fun buildPrompt(batch: List<TranscriptSegment>) = buildString {
            appendLine("Correct these transcript segments:")
            batch.forEachIndexed { index, segment -> appendLine("$index|${segment.text}") }
        }.trimEnd()

        internal fun parseResponse(
            responseText: String,
            originalBatch: List<TranscriptSegment>,
        ): List<TranscriptSegment> {
            val correctedTexts = mutableMapOf<Int, String>()

            for (line in responseText.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val pipeIndex = trimmed.indexOf('|')
                if (pipeIndex < 0) continue

                val indexStr = trimmed.substring(0, pipeIndex).trim()
                val correctedText = trimmed.substring(pipeIndex + 1)

                val index = indexStr.toIntOrNull() ?: continue
                if (index in originalBatch.indices) {
                    correctedTexts[index] = correctedText
                }
            }

            if (correctedTexts.isEmpty()) return originalBatch

            return originalBatch.mapIndexed { index, segment ->
                correctedTexts[index]?.let { segment.copy(text = it) } ?: segment
            }
        }

        fun defaultClient() = HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}

@Serializable
private data class CorrectionRequest(
    val model: String,
    val prompt: String,
    val system: String,
    val stream: Boolean,
    val options: Map<String, Double> = mapOf("temperature" to 0.0),
    @SerialName("num_predict") val numPredict: Int = 1024,
)

@Serializable
private data class CorrectionResponse(
    val response: String = "",
    val done: Boolean = true,
    val error: String? = null,
)
