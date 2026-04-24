package com.meetingnotes.data.llm

import com.meetingnotes.domain.llm.LlmProvider
import com.meetingnotes.domain.llm.LlmTimeoutException
import com.meetingnotes.domain.llm.LlmUnavailableException
import com.meetingnotes.domain.llm.SummaryParser
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.transcription.TranscriptCorrectionService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketTimeoutException

class OllamaProvider(private val httpClient: HttpClient = defaultClient()) : LlmProvider {

    override val name = "Ollama (local)"

    override suspend fun isAvailable(): Boolean = runCatching {
        httpClient.get("http://localhost:11434/api/tags")
    }.isSuccess

    override suspend fun summarize(segments: List<TranscriptSegment>, settings: AppSettings): MeetingSummary {
        val transcript = buildTranscript(segments)
        val userMessage = "Transcript:\n\n$transcript"

        val request = OllamaGenerateRequest(
            model = settings.llmModel,
            prompt = userMessage,
            system = LlmProvider.SYSTEM_PROMPT,
            stream = false,
        )

        val response = runCatching {
            httpClient.post("${settings.llmBaseUrl}/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<OllamaGenerateResponse>()
        }.getOrElse { cause ->
            when (cause) {
                is HttpRequestTimeoutException, is SocketTimeoutException -> throw LlmTimeoutException("Ollama request timed out after 120s", cause)
                is ConnectException -> throw LlmUnavailableException("Cannot connect to Ollama at ${settings.llmBaseUrl}", cause)
                else -> throw LlmUnavailableException("Ollama request failed: ${cause.message}", cause)
            }
        }

        if (!response.error.isNullOrBlank()) {
            throw LlmUnavailableException("Ollama error: ${response.error}")
        }

        return SummaryParser.parse(meetingId = "", rawResponse = response.response)
    }

    override suspend fun correct(segments: List<TranscriptSegment>, settings: AppSettings): List<TranscriptSegment> =
        TranscriptCorrectionService(httpClient).correct(segments, settings.llmModel, settings.llmBaseUrl)

    private fun buildTranscript(segments: List<TranscriptSegment>): String = buildString {
        for (seg in segments) {
            val ts = formatTimestamp(seg.startMs)
            val speaker = seg.speakerLabel ?: "Unknown"
            appendLine("[$ts] $speaker: ${seg.text}")
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
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
private data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val system: String,
    val stream: Boolean,
    @SerialName("num_predict") val numPredict: Int = 2048,
)

@Serializable
private data class OllamaGenerateResponse(
    val model: String = "",
    val response: String = "",  // default empty — Ollama error payloads omit this field
    val done: Boolean = true,
    val error: String? = null,
)
