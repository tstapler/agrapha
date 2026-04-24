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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketTimeoutException

class OpenAiProvider(private val httpClient: HttpClient = OllamaProvider.defaultClient()) : LlmProvider {

    override val name = "OpenAI"

    override suspend fun isAvailable(): Boolean = true  // no lightweight ping for OpenAI

    override suspend fun summarize(segments: List<TranscriptSegment>, settings: AppSettings): MeetingSummary {
        val apiKey = settings.llmApiKey
            ?: throw LlmAuthException("OpenAI API key not configured")

        val messages = listOf(
            ChatMessage(role = "system", content = LlmProvider.SYSTEM_PROMPT),
            ChatMessage(role = "user", content = buildTranscript(segments)),
        )

        val response = runCatching {
            val resp = httpClient.post("https://api.openai.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey)
                setBody(ChatCompletionRequest(model = settings.llmModel, messages = messages))
            }
            when (resp.status) {
                HttpStatusCode.Unauthorized -> throw LlmAuthException("Invalid OpenAI API key (401)")
                HttpStatusCode.TooManyRequests -> throw LlmRateLimitException("OpenAI rate limit exceeded (429)")
                HttpStatusCode.NotFound -> throw LlmUnavailableException("OpenAI model '${settings.llmModel}' not found (404)")
                else -> resp.body<ChatCompletionResponse>()
            }
        }.getOrElse { cause ->
            when (cause) {
                is LlmAuthException, is LlmRateLimitException, is LlmUnavailableException -> throw cause
                is HttpRequestTimeoutException, is SocketTimeoutException -> throw LlmTimeoutException("OpenAI request timed out", cause)
                is ConnectException -> throw LlmUnavailableException("Cannot connect to OpenAI", cause)
                else -> throw LlmUnavailableException("OpenAI request failed: ${cause.message}", cause)
            }
        }

        val content = response.choices.firstOrNull()?.message?.content ?: ""
        return SummaryParser.parse(meetingId = "", rawResponse = content)
    }

    override suspend fun correct(segments: List<TranscriptSegment>, settings: AppSettings): List<TranscriptSegment> {
        if (segments.isEmpty()) return segments
        val apiKey = settings.llmApiKey ?: return segments
        val result = mutableListOf<TranscriptSegment>()
        for (batchStart in segments.indices step TranscriptCorrectionService.BATCH_SIZE) {
            val batch = segments.subList(batchStart, minOf(batchStart + TranscriptCorrectionService.BATCH_SIZE, segments.size))
            val correctedBatch = runCatching {
                val messages = listOf(
                    ChatMessage(role = "system", content = TranscriptCorrectionService.SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = TranscriptCorrectionService.buildPrompt(batch)),
                )
                val resp = httpClient.post("https://api.openai.com/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    bearerAuth(apiKey)
                    setBody(ChatCompletionRequest(model = settings.llmModel, messages = messages, maxTokens = 1024, temperature = 0.0))
                }
                val content = resp.body<ChatCompletionResponse>().choices.firstOrNull()?.message?.content ?: ""
                TranscriptCorrectionService.parseResponse(content, batch)
            }.getOrDefault(batch)
            result.addAll(correctedBatch)
        }
        return result
    }

    private fun buildTranscript(segments: List<TranscriptSegment>) = segments.joinToString("\n") { seg ->
        val ts = formatTs(seg.startMs)
        "[${ts}] ${seg.speakerLabel ?: "Unknown"}: ${seg.text}"
    }

    private fun formatTs(ms: Long): String {
        val s = ms / 1000; val m = s / 60; val sec = s % 60
        return "%02d:%02d".format(m, sec)
    }
}

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    val temperature: Double = 0.3,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(val message: ChatMessage)
}
