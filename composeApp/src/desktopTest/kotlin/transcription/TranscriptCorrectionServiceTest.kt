package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals

class TranscriptCorrectionServiceTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun makeSegment(index: Int, text: String) = TranscriptSegment(
        id = "seg-$index",
        meetingId = "meeting-1",
        speakerLabel = "You",
        startMs = index * 1000L,
        endMs = (index + 1) * 1000L,
        text = text,
    )

    private fun makeClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    // ── Test 1 ────────────────────────────────────────────────────────────────

    @Test
    fun `correct returns original segments when HTTP call fails`() = runTest {
        val engine = MockEngine { throw IOException("Connection refused") }
        val client = makeClient(engine)
        try {
            val segments = listOf(makeSegment(0, "the piper line is failing"))
            val result = TranscriptCorrectionService(client).correct(
                segments,
                model = "llama3.2",
                baseUrl = "http://localhost:11434",
            )
            assertEquals(segments, result)
        } finally { client.close() }
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────

    @Test
    fun `correct returns original segments when response is malformed`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"response": "this line has no pipe separator", "done": true}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = makeClient(engine)
        try {
            val segments = listOf(makeSegment(0, "the piper line is failing"))
            val result = TranscriptCorrectionService(client).correct(
                segments,
                model = "llama3.2",
                baseUrl = "http://localhost:11434",
            )
            assertEquals(segments, result)
        } finally { client.close() }
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────

    @Test
    fun `correct applies corrections when response is valid`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"response": "0|the pipeline is failing\n1|we need to deploy the new version", "done": true}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = makeClient(engine)
        try {
            val segments = listOf(
                makeSegment(0, "the piper line is failing"),
                makeSegment(1, "we need to deeploy the new version"),
            )
            val result = TranscriptCorrectionService(client).correct(
                segments,
                model = "llama3.2",
                baseUrl = "http://localhost:11434",
            )
            assertEquals(2, result.size)
            assertEquals("the pipeline is failing", result[0].text)
            assertEquals("we need to deploy the new version", result[1].text)
            // Metadata preserved
            assertEquals("seg-0", result[0].id)
            assertEquals("meeting-1", result[0].meetingId)
            assertEquals("You", result[0].speakerLabel)
            assertEquals(0L, result[0].startMs)
            assertEquals(1000L, result[0].endMs)
        } finally { client.close() }
    }

    // ── Test 4 ────────────────────────────────────────────────────────────────

    @Test
    fun `correct returns immediately for empty segment list`() = runTest {
        // No engine calls expected — if the engine is hit the test will fail
        val engine = MockEngine { error("should not be called") }
        val client = makeClient(engine)
        try {
            val result = TranscriptCorrectionService(client).correct(
                emptyList(),
                model = "llama3.2",
                baseUrl = "http://localhost:11434",
            )
            assertEquals(emptyList(), result)
        } finally { client.close() }
    }

    // ── Test 5 ────────────────────────────────────────────────────────────────

    @Test
    fun `correct processes segments in batches of 20`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            // Return an empty response — segments fall back to originals via fail-safe
            respond(
                content = """{"response": "", "done": true}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = makeClient(engine)
        try {
            // 45 segments → 3 batches (20 + 20 + 5)
            val segments = (0 until 45).map { makeSegment(it, "text $it") }
            val result = TranscriptCorrectionService(client).correct(
                segments,
                model = "llama3.2",
                baseUrl = "http://localhost:11434",
            )
            assertEquals(3, callCount, "Expected 3 HTTP calls for 45 segments in batches of 20")
            assertEquals(45, result.size)
        } finally { client.close() }
    }
}
