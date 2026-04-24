package com.meetingnotes.data.llm

import com.meetingnotes.domain.llm.LlmAuthException
import com.meetingnotes.domain.llm.LlmRateLimitException
import com.meetingnotes.domain.llm.LlmTimeoutException
import com.meetingnotes.domain.llm.LlmUnavailableException
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.LlmProvider as LlmProviderEnum
import com.meetingnotes.domain.model.TranscriptSegment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * S4-UNIT-05: OllamaProvider happy path.
 * S4-UNIT-06: OllamaProvider HttpRequestTimeoutException → LlmTimeoutException.
 * S4-UNIT-07: OllamaProvider connection refused → LlmUnavailableException.
 * S4-UNIT-08: OpenAiProvider 401 → LlmAuthException.
 * S4-UNIT-09: AnthropicProvider 429 → LlmRateLimitException.
 * S4-UNIT-10: LlmProviderFactory returns correct type.
 *
 * Each test closes its HttpClient in a finally block to prevent uncaught
 * Ktor background-coroutine exceptions from leaking into subsequent tests.
 */
class LlmProviderTest {

    private val segments = listOf(
        TranscriptSegment("s1", "m1", "You", 0L, 2000L, "Hello, let's discuss the roadmap."),
    )

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    // ── S4-UNIT-05 ────────────────────────────────────────────────────────────

    @Test
    fun `OllamaProvider happy path returns MeetingSummary`() = runTest {
        val responseBody = """{"model":"llama3.2","response":"## Key Points\n- Discussed roadmap","done":true}"""
        val client = HttpClient(MockEngine { respond(responseBody, headers = jsonHeaders) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            val summary = OllamaProvider(client).summarize(segments, AppSettings(llmModel = "llama3.2"))
            assertIs<com.meetingnotes.domain.model.MeetingSummary>(summary)
        } finally { client.close() }
    }

    // ── S4-UNIT-05b ───────────────────────────────────────────────────────────

    @Test
    fun `OllamaProvider throws LlmUnavailableException when response contains error field`() = runTest {
        val responseBody = """{"model":"llama3.2","response":"","done":true,"error":"model not found"}"""
        val client = HttpClient(MockEngine { respond(responseBody, headers = jsonHeaders) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            assertFailsWith<LlmUnavailableException> {
                OllamaProvider(client).summarize(segments, AppSettings(llmModel = "llama3.2"))
            }
        } finally { client.close() }
    }

    // ── S4-UNIT-06 ────────────────────────────────────────────────────────────

    @Test
    fun `OllamaProvider throws LlmTimeoutException when HttpRequestTimeoutException occurs`() = runTest {
        val client = HttpClient(MockEngine { throw io.ktor.client.plugins.HttpRequestTimeoutException("test", null) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            assertFailsWith<LlmTimeoutException> {
                OllamaProvider(client).summarize(segments, AppSettings())
            }
        } finally { client.close() }
    }

    // ── S4-UNIT-07 ────────────────────────────────────────────────────────────

    @Test
    fun `OllamaProvider throws LlmUnavailableException when connection refused`() = runTest {
        val client = HttpClient(MockEngine { throw IOException("Connection refused") }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            assertFailsWith<LlmUnavailableException> {
                OllamaProvider(client).summarize(segments, AppSettings())
            }
        } finally { client.close() }
    }

    // ── S4-UNIT-08 ────────────────────────────────────────────────────────────

    @Test
    fun `OpenAiProvider throws LlmAuthException on 401`() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.Unauthorized, "Unauthorized") }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            assertFailsWith<LlmAuthException> {
                OpenAiProvider(client).summarize(segments, AppSettings(llmApiKey = "sk-invalid"))
            }
        } finally { client.close() }
    }

    @Test
    fun `OpenAiProvider throws LlmAuthException when API key is null`() {
        // Auth check is synchronous before any HTTP call
        assertFailsWith<LlmAuthException> {
            runTest { OpenAiProvider().summarize(segments, AppSettings(llmApiKey = null)) }
        }
    }

    // ── S4-UNIT-09 ────────────────────────────────────────────────────────────

    @Test
    fun `AnthropicProvider throws LlmRateLimitException on 429`() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.TooManyRequests, "Rate limit exceeded") }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            assertFailsWith<LlmRateLimitException> {
                AnthropicProvider(client).summarize(segments, AppSettings(llmApiKey = "sk-ant-test"))
            }
        } finally { client.close() }
    }

    @Test
    fun `AnthropicProvider throws LlmAuthException when API key is null`() {
        // Auth check is synchronous before any HTTP call
        assertFailsWith<LlmAuthException> {
            runTest { AnthropicProvider().summarize(segments, AppSettings(llmApiKey = null)) }
        }
    }

    // ── S4-UNIT-10 ────────────────────────────────────────────────────────────

    @Test
    fun `LlmProviderFactory creates OllamaProvider for OLLAMA setting`() {
        assertIs<OllamaProvider>(LlmProviderFactory.create(AppSettings(llmProvider = LlmProviderEnum.OLLAMA)))
    }

    @Test
    fun `LlmProviderFactory creates OpenAiProvider for OPENAI setting`() {
        assertIs<OpenAiProvider>(LlmProviderFactory.create(AppSettings(llmProvider = LlmProviderEnum.OPENAI)))
    }

    @Test
    fun `LlmProviderFactory creates AnthropicProvider for ANTHROPIC setting`() {
        assertIs<AnthropicProvider>(LlmProviderFactory.create(AppSettings(llmProvider = LlmProviderEnum.ANTHROPIC)))
    }
}
