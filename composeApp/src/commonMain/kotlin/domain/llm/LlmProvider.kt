package com.meetingnotes.domain.llm

import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment

/** Thrown when the LLM service is unreachable or returns a connection error. */
class LlmUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the request exceeds the configured timeout. */
class LlmTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the API key is missing or rejected (HTTP 401). */
class LlmAuthException(message: String) : Exception(message)

/** Thrown when the API rate limit is exceeded (HTTP 429). */
class LlmRateLimitException(message: String, val retryAfterSeconds: Int? = null) : Exception(message)

/**
 * Strategy interface for LLM providers.
 *
 * Implementations: [OllamaProvider], [OpenAiProvider], [AnthropicProvider].
 */
interface LlmProvider {

    /** Human-readable display name (e.g. "Ollama (local)"). */
    val name: String

    /**
     * Check whether the provider is reachable.
     * For Ollama this is a GET /api/tags; for cloud providers it's a lightweight ping.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Summarise [segments] and return a structured [MeetingSummary].
     *
     * @throws LlmUnavailableException if the provider cannot be reached
     * @throws LlmTimeoutException if the request exceeds 120 seconds
     * @throws LlmAuthException for 401 responses
     * @throws LlmRateLimitException for 429 responses
     */
    suspend fun summarize(segments: List<TranscriptSegment>, settings: AppSettings): MeetingSummary

    /**
     * Correct ASR transcription errors in [segments].
     *
     * Must be fail-safe: on any error, return the original segments unchanged.
     */
    suspend fun correct(segments: List<TranscriptSegment>, settings: AppSettings): List<TranscriptSegment>

    companion object {
        /** System prompt template requesting structured JSON output. */
        val SYSTEM_PROMPT = """
            You are a meeting notes assistant. Given a meeting transcript, extract a structured summary.

            Respond with ONLY valid JSON in this exact format:
            {
              "keyPoints": ["point 1", "point 2"],
              "decisions": ["decision 1"],
              "actionItems": [
                {"owner": "Alice", "text": "Write spec", "dueDate": "2026-04-10"},
                {"owner": null, "text": "Schedule follow-up", "dueDate": null}
              ],
              "discussionPoints": ["topic 1: detail", "topic 2: detail"]
            }

            Rules:
            - keyPoints: 3-7 bullet points summarising the main discussion
            - decisions: only explicit decisions made, not topics discussed
            - actionItems: each must have "text"; "owner" and "dueDate" are optional
            - discussionPoints: major topics with brief summaries
            - Do NOT include markdown fences, preamble, or explanation — JSON only
        """.trimIndent()
    }
}
