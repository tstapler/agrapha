package com.meetingnotes.transcription

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [WhisperModelDownloader].
 *
 * - isAlreadyDownloaded: true when file size matches, false when missing or wrong size
 * - SHA mismatch: emits Error state and deletes corrupt file
 * - Happy path: download → verify → Done state
 */
class WhisperModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testSpec = WhisperModelSpec(
        filename = "test-model.bin",
        displayName = "Test Model",
        description = "For testing",
        sha256 = "",  // overridden per test
        sizeBytes = 0L,  // overridden per test
    )

    // ── isAlreadyDownloaded ───────────────────────────────────────────────────

    @Test
    fun `isAlreadyDownloaded returns false when file does not exist`() {
        val spec = testSpec.copy(sizeBytes = 100L)
        val downloader = WhisperModelDownloader()
        assertFalse(downloader.isAlreadyDownloaded(spec, tmp.root))
    }

    @Test
    fun `isAlreadyDownloaded returns false when file size differs`() {
        val file = tmp.newFile("test-model.bin")
        file.writeBytes(ByteArray(50))  // wrong size
        val spec = testSpec.copy(sizeBytes = 100L)

        val downloader = WhisperModelDownloader()
        assertFalse(downloader.isAlreadyDownloaded(spec, tmp.root))
    }

    @Test
    fun `isAlreadyDownloaded returns true when file exists with correct size`() {
        val file = tmp.newFile("test-model.bin")
        file.writeBytes(ByteArray(100))
        val spec = testSpec.copy(sizeBytes = 100L)

        val downloader = WhisperModelDownloader()
        assertTrue(downloader.isAlreadyDownloaded(spec, tmp.root))
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `download emits Done state with correct path on success`() = runTest {
        val payload = ByteArray(256) { it.toByte() }
        val sha = sha256Hex(payload)
        val spec = testSpec.copy(sha256 = sha, sizeBytes = payload.size.toLong())

        val client = HttpClient(MockEngine { respond(payload) })
        val downloader = WhisperModelDownloader(client)

        val states = downloader.download(spec, tmp.root).toList()

        val doneState = states.filterIsInstance<ModelDownloadState.Done>().lastOrNull()
        assertTrue(doneState != null, "Must emit Done state; got: $states")
        assertTrue(doneState.path.endsWith("test-model.bin"), "Done path must point to model file")
        assertTrue(File(doneState.path).exists(), "Downloaded file must exist")
        downloader.close()
    }

    @Test
    fun `download passes through Downloading progress states`() = runTest {
        val payload = ByteArray(2 * 1024 * 1024) { it.toByte() }  // 2MB — triggers progress emit
        val sha = sha256Hex(payload)
        val spec = testSpec.copy(sha256 = sha, sizeBytes = payload.size.toLong())

        val client = HttpClient(MockEngine { respond(payload) })
        val downloader = WhisperModelDownloader(client)

        val states = downloader.download(spec, tmp.root).toList()

        val downloadingStates = states.filterIsInstance<ModelDownloadState.Downloading>()
        assertTrue(downloadingStates.isNotEmpty(), "Must emit at least one Downloading state")
        downloader.close()
    }

    // ── SHA mismatch ──────────────────────────────────────────────────────────

    @Test
    fun `download emits Error and deletes file on SHA mismatch`() = runTest {
        val payload = ByteArray(64) { 0xAB.toByte() }
        val wrongSha = "0".repeat(64)  // definitely wrong
        val spec = testSpec.copy(sha256 = wrongSha, sizeBytes = payload.size.toLong())

        val client = HttpClient(MockEngine { respond(payload) })
        val downloader = WhisperModelDownloader(client)

        val states = downloader.download(spec, tmp.root).toList()

        val errorState = states.filterIsInstance<ModelDownloadState.Error>().lastOrNull()
        assertTrue(errorState != null, "Must emit Error state on SHA mismatch; got: $states")
        assertTrue(errorState.message.contains("SHA-256"), "Error message must mention SHA-256")

        // Corrupt file must be deleted
        assertFalse(File(tmp.root, "test-model.bin").exists(), "Corrupt file must be deleted after mismatch")
        downloader.close()
    }

    // ── Re-verification of existing file ─────────────────────────────────────

    @Test
    fun `download re-verifies existing file and skips network if SHA matches`() = runTest {
        val payload = ByteArray(64) { 0x42.toByte() }
        val sha = sha256Hex(payload)
        val spec = testSpec.copy(sha256 = sha, sizeBytes = payload.size.toLong())

        // Pre-write the file to simulate an already-downloaded model
        val modelFile = File(tmp.root, spec.filename)
        modelFile.writeBytes(payload)

        var requestCount = 0
        val client = HttpClient(MockEngine {
            requestCount++
            respond(payload)
        })
        val downloader = WhisperModelDownloader(client)

        val states = downloader.download(spec, tmp.root).toList()

        val doneState = states.filterIsInstance<ModelDownloadState.Done>().lastOrNull()
        assertTrue(doneState != null, "Must emit Done for valid existing file")
        assertTrue(requestCount == 0, "Must not make network request for valid existing file")
        downloader.close()
    }

    @Test
    fun `download re-downloads when existing file has wrong SHA`() = runTest {
        val goodPayload = ByteArray(64) { 0x42.toByte() }
        val goodSha = sha256Hex(goodPayload)
        val spec = testSpec.copy(sha256 = goodSha, sizeBytes = goodPayload.size.toLong())

        // Write corrupt content
        val modelFile = File(tmp.root, spec.filename)
        modelFile.writeBytes(ByteArray(64) { 0xFF.toByte() })  // wrong content, same size

        var requestCount = 0
        val client = HttpClient(MockEngine {
            requestCount++
            respond(goodPayload)
        })
        val downloader = WhisperModelDownloader(client)

        val states = downloader.download(spec, tmp.root).toList()

        val doneState = states.filterIsInstance<ModelDownloadState.Done>().lastOrNull()
        assertTrue(doneState != null, "Must emit Done after re-downloading")
        assertTrue(requestCount == 1, "Must make exactly 1 network request to re-download")
        downloader.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
