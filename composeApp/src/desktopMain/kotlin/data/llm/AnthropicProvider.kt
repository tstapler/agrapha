package com.meetingnotes.data.llm

import com.meetingnotes.domain.llm.LlmAuthException
import com.meetingnotes.domain.llm.LlmProvider
import com.meetingnotes.domain.llm.LlmRateLimitException
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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.ConnectException
import java.net.SocketTimeoutException

class AnthropicProvider(private val httpClient: HttpClient = OllamaProvider.defaultClient()) : LlmProvider {

    override val name = "Anthropic"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun summarize(segments: List<TranscriptSegment>, settings: AppSettings): MeetingSummary {
        val apiKey = settings.llmApiKey
            ?: throw LlmAuthException("Anthropic API key not configured")

        val response = runCatching {
            val resp = httpClient.post("https://api.anthropic.com/v1/messages") {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                setBody(
                    AnthropicRequest(
                        model = settings.llmModel,
                        maxTokens = 2048,
                        system = LlmProvider.SYSTEM_PROMPT,
                        messages = listOf(AnthropicMessage("user", buildTranscript(segments))),
                    )
                )
            }
            when (resp.status) {
                HttpStatusCode.Unauthorized -> throw LlmAuthException("Invalid Anthropic API key (401)")
                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = resp.headers["retry-after"]?.toIntOrNull()
                    throw LlmRateLimitException("Anthropic rate limit exceeded (429)", retryAfter)
                }
                HttpStatusCode.NotFound -> throw LlmUnavailableException("Anthropic model '${settings.llmModel}' not found (404)")
                else -> resp.body<AnthropicResponse>()
            }
        }.getOrElse { cause ->
            when (cause) {
                is LlmAuthException, is LlmRateLimitException, is LlmUnavailableException -> throw cause
                is HttpRequestTimeoutException, is SocketTimeoutException -> throw LlmTimeoutException("Anthropic request timed out", cause)
                is ConnectException -> throw LlmUnavailableException("Cannot connect to Anthropic API", cause)
                else -> throw LlmUnavailableException("Anthropic request failed: ${cause.message}", cause)
            }
        }

        val content = response.content.firstOrNull()?.text ?: ""
        return SummaryParser.parse(meetingId = "", rawResponse = content)
    }

    override suspend fun correct(segments: List<TranscriptSegment>, settings: AppSettings): List<TranscriptSegment> {
        if (segments.isEmpty()) return segments
        val apiKey = settings.llmApiKey ?: return segments
        val result = mutableListOf<TranscriptSegment>()
        for (batchStart in segments.indices step TranscriptCorrectionService.BATCH_SIZE) {
            val batch = segments.subList(batchStart, minOf(batchStart + TranscriptCorrectionService.BATCH_SIZE, segments.size))
            val correctedBatch = runCatching {
                val resp = httpClient.post("https://api.anthropic.com/v1/messages") {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                    setBody(AnthropicRequest(
                        model = settings.llmModel,
                        maxTokens = 1024,
                        system = TranscriptCorrectionService.SYSTEM_PROMPT,
                        messages = listOf(AnthropicMessage("user", TranscriptCorrectionService.buildPrompt(batch))),
                    ))
                }
                val content = resp.body<AnthropicResponse>().content.firstOrNull()?.text ?: ""
                TranscriptCorrectionService.parseResponse(content, batch)
            }.getOrDefault(batch)
            result.addAll(correctedBatch)
        }
        return result
    }

    private fun buildTranscript(segments: List<TranscriptSegment>) = segments.joinToString("\n") { seg ->
        val s = seg.startMs / 1000; val m = s / 60; val sec = s % 60
        "[%02d:%02d] ${seg.speakerLabel ?: "Unknown"}: ${seg.text}".format(m, sec)
    }
}

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
)

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicResponse(val content: List<ContentBlock> = emptyList()) {
    @Serializable
    data class ContentBlock(val type: String = "text", val text: String = "")
}
