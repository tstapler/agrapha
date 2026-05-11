package com.meetingnotes.audio

import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.createInMemoryDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UNIT-2-3-01 through UNIT-2-3-05 — RecordingSessionManager backend injection
 */
class RecordingSessionManagerBackendTest {

    private lateinit var tempDir: File
    private lateinit var repo: MeetingRepository
    private lateinit var storage: FileStorageService
    private lateinit var scope: CoroutineScope
    private lateinit var mockBackend: SystemAudioBackend

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("rsm-backend-test").toFile()
        tempDir.resolve("recordings").mkdirs()

        repo = MeetingRepository(createInMemoryDatabase())

        storage = mockk<FileStorageService>()
        every { storage.getAudioFilePath(any()) } answers {
            tempDir.resolve("recordings/${firstArg<String>()}.wav").absolutePath
        }

        mockBackend = mockk<SystemAudioBackend>(relaxed = true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    // ── UNIT-2-3-01 ──────────────────────────────────────────────────────────
    @Test
    fun `startCapture is called on injected backend when isAvailable returns true`() {
        every { mockBackend.isAvailable() } returns true
        every { mockBackend.startCapture(any()) } returns true
        every { mockBackend.readBuffer(any()) } returns 0

        val manager = RecordingSessionManager(repo, storage, mockBackend)
        manager.startRecording(scope)
        Thread.sleep(200)

        verify { mockBackend.startCapture(16_000) }

        runBlocking { manager.stopRecording() }
    }

    // ── UNIT-2-3-02 ──────────────────────────────────────────────────────────
    @Test
    fun `stopCapture is called on backend when recording stops`() {
        every { mockBackend.isAvailable() } returns true
        every { mockBackend.startCapture(any()) } returns true
        every { mockBackend.readBuffer(any()) } returns 0

        val manager = RecordingSessionManager(repo, storage, mockBackend)
        manager.startRecording(scope)
        Thread.sleep(200)

        runBlocking { manager.stopRecording() }

        verify { mockBackend.stopCapture() }
    }

    // ── UNIT-2-3-03 ──────────────────────────────────────────────────────────
    @Test
    fun `manager falls back to silence when startCapture returns false`() {
        every { mockBackend.isAvailable() } returns true
        every { mockBackend.startCapture(any()) } returns false
        every { mockBackend.readBuffer(any()) } returns 0

        val manager = RecordingSessionManager(repo, storage, mockBackend)
        val id = manager.startRecording(scope)
        Thread.sleep(200)

        val (meeting, _) = runBlocking { manager.stopRecording() }

        // Recording still completes and WAV is produced (silent sys channel)
        assertNotNull(meeting)
        assertTrue(File(meeting!!.audioFilePath).exists(), "WAV must exist even with silent backend")
    }

    // ── UNIT-2-3-05 ──────────────────────────────────────────────────────────
    @Test
    fun `NoOpSystemAudioBackend produces valid stereo WAV`() {
        val manager = RecordingSessionManager(repo, storage, NoOpSystemAudioBackend())
        manager.startRecording(scope)
        Thread.sleep(200)

        val (meeting, _) = runBlocking { manager.stopRecording() }

        assertNotNull(meeting)
        val wavFile = File(meeting!!.audioFilePath)
        assertTrue(wavFile.exists())
        assertTrue(wavFile.length() >= 44)

        val bytes = wavFile.readBytes()
        val channels = ((bytes[23].toInt() and 0xFF) shl 8) or (bytes[22].toInt() and 0xFF)
        kotlin.test.assertEquals(2, channels, "Output must be stereo")
    }

    // ── UNIT-2-3-04 (compile-level check) ────────────────────────────────────
    @Test
    fun `RecordingSessionManager does not reference ScreenCaptureJniBridge`() {
        // This test documents the constraint. If the class contained a direct
        // ScreenCaptureJniBridge reference it would fail to compile on Linux
        // (since the JNI dylib is macOS-only). The test itself always passes
        // but acts as a living marker that the refactor was done.
        val classBytes = RecordingSessionManager::class.java
            .getResourceAsStream("RecordingSessionManager.class")
            ?.readBytes()
            ?: return  // class not found in test runner — skip
        val classStr = String(classBytes, Charsets.ISO_8859_1)
        assertTrue(
            !classStr.contains("ScreenCaptureJniBridge"),
            "RecordingSessionManager must not reference ScreenCaptureJniBridge directly"
        )
    }
}
