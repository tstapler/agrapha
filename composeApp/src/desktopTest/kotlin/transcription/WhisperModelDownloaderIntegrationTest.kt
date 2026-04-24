package com.meetingnotes.transcription

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.request.head
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live network integration tests for [WhisperModelDownloader].
 *
 * These tests hit the Hugging Face CDN and write to disk. They are excluded from
 * normal CI runs (all annotated @Ignore) and must be executed manually:
 *
 *   ./gradlew :composeApp:desktopTest --tests "*.WhisperModelDownloaderIntegrationTest" -Pintegration
 *
 * Prerequisites:
 *   - Internet connectivity
 *   - ~80 MB free disk space (tiny model download)
 *
 * Each test cleans up its downloaded files via TemporaryFolder.
 */
@Ignore("Live network tests — run manually: see class KDoc for instructions")
class WhisperModelDownloaderIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var client: HttpClient
    private lateinit var downloader: WhisperModelDownloader

    // Use the smallest model (tiny.en, 77 MB) to keep tests fast.
    private val tinySpec = WHISPER_MODELS.first { it.filename == "ggml-tiny.en.bin" }

    @Before
    fun setUp() {
        client = HttpClient(CIO) {
            install(HttpRedirect) { checkHttpMethod = false }
            engine { requestTimeout = 0 }
        }
        downloader = WhisperModelDownloader(client)
    }

    @After
    fun tearDown() {
        downloader.close()
    }

    // ── Connectivity ──────────────────────────────────────────────────────────

    @Test
    fun `Hugging Face model URL is reachable`() = runTest {
        val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${tinySpec.filename}"
        val response = client.head(url)
        assertTrue(
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Found,
            "Expected 200 or 302 from HuggingFace, got: ${response.status}"
        )
    }

    @Test
    fun `Hugging Face CoreML encoder URL is reachable`() = runTest {
        val encoderBase = tinySpec.coremlBaseEncoder ?: return@runTest
        val url = "https://huggingface.co/ggerganov/whisper.cpp-coreml/resolve/main/$encoderBase-encoder.mlmodelc.zip"
        val response = client.head(url)
        assertTrue(
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Found,
            "Expected 200 or 302 from HuggingFace CoreML repo, got: ${response.status}"
        )
    }

    // ── Full download with SHA verification ───────────────────────────────────

    @Test
    fun `download tiny model completes with verified SHA-256`() = runTest {
        val states = downloader.download(tinySpec, tmp.root).toList()

        val done = states.filterIsInstance<ModelDownloadState.Done>().lastOrNull()
        assertNotNull(done, "Must emit Done state; got states: ${states.map { it::class.simpleName }}")

        val modelFile = File(done.path)
        assertTrue(modelFile.exists(), "Model file must exist at reported path")
        assertEquals(tinySpec.sizeBytes, modelFile.length(), "Downloaded file size must match spec")
    }

    @Test
    fun `download emits Downloading progress states for large file`() = runTest {
        val states = downloader.download(tinySpec, tmp.root).toList()

        val downloadingStates = states.filterIsInstance<ModelDownloadState.Downloading>()
        assertTrue(downloadingStates.size >= 10, "Expected ≥10 progress updates for a 77 MB file")

        // Progress must be monotonically non-decreasing
        downloadingStates.zipWithNext().forEach { (a, b) ->
            assertTrue(b.bytesReceived >= a.bytesReceived, "Progress must not decrease: $a → $b")
        }
    }

    @Test
    fun `download emits Verifying state before Done`() = runTest {
        val states = downloader.download(tinySpec, tmp.root).toList()

        val verifyIndex = states.indexOfFirst { it is ModelDownloadState.Verifying }
        val doneIndex = states.indexOfFirst { it is ModelDownloadState.Done }

        assertTrue(verifyIndex >= 0, "Must emit Verifying state")
        assertTrue(doneIndex > verifyIndex, "Done must come after Verifying")
    }

    // ── Re-verification path ──────────────────────────────────────────────────

    @Test
    fun `second download call skips network when file already verified`() = runTest {
        // First download — hits the network
        val firstStates = downloader.download(tinySpec, tmp.root).toList()
        assertIs<ModelDownloadState.Done>(firstStates.last())

        // Second download — must short-circuit after Verifying without any Downloading states
        val secondStates = downloader.download(tinySpec, tmp.root).toList()

        val downloadingInSecond = secondStates.filterIsInstance<ModelDownloadState.Downloading>()
        assertTrue(downloadingInSecond.isEmpty(), "Second call must not emit Downloading states for already-verified file")
        assertIs<ModelDownloadState.Done>(secondStates.last())
    }

    @Test
    fun `partial download triggers full re-download`() = runTest {
        // Pre-write a file with correct size but wrong content (simulates corrupt partial download)
        val corruptFile = File(tmp.root, tinySpec.filename)
        corruptFile.writeBytes(ByteArray(tinySpec.sizeBytes.toInt().coerceAtMost(1024)))

        // Pad to match expected size so isAlreadyDownloaded passes the size check
        corruptFile.outputStream().use { out ->
            val buf = ByteArray(8192) { 0xFF.toByte() }
            var remaining = tinySpec.sizeBytes
            while (remaining > 0) {
                val chunk = minOf(remaining, buf.size.toLong()).toInt()
                out.write(buf, 0, chunk)
                remaining -= chunk
            }
        }

        val states = downloader.download(tinySpec, tmp.root).toList()

        // Must re-download because SHA won't match despite size match
        val downloadingStates = states.filterIsInstance<ModelDownloadState.Downloading>()
        assertTrue(downloadingStates.isNotEmpty(), "Must re-download when existing file fails SHA verification")

        val done = states.filterIsInstance<ModelDownloadState.Done>().lastOrNull()
        assertNotNull(done, "Must complete with Done state after re-download")
    }
}
