package com.meetingnotes.domain

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.createInMemoryDatabase
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.transcription.WhisperService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [PipelineQueueExecutor].
 *
 * Verifies that:
 * - enqueue() adds the meeting to processingIds
 * - Processing completes and removes from processingIds
 * - [WhisperService] is injectable (no hardcoded internal construction)
 *
 * These tests use a mock WhisperService so no real model file is needed.
 * The pipeline stages (LLM, export) are skipped naturally because:
 * - LlmUnavailableException is thrown (Ollama not configured) → summarySkipped=true
 * - logseqWikiPath is blank → export is skipped
 */
class PipelineQueueExecutorTest {

    private lateinit var repo: MeetingRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var whisperMock: WhisperService
    private lateinit var executor: PipelineQueueExecutor

    private val testMeeting = Meeting(
        id = "test-meeting-queue-001",
        title = "Queue Test Meeting",
        startedAt = 1_000_000L,
        audioFilePath = "/nonexistent/audio.wav",
    )

    @Before
    fun setUp() {
        repo = MeetingRepository(createInMemoryDatabase())
        settingsRepo = SettingsRepository(createInMemoryDatabase())

        // Save settings with no LLM key and no wiki path so LLM/export are skipped
        settingsRepo.save(AppSettings(
            whisperModelPath = "",
            logseqWikiPath = "",
        ))

        whisperMock = mockk<WhisperService>(relaxed = true)
        every { whisperMock.isLoaded } returns false
        every { whisperMock.loadedModelPath } returns null

        executor = PipelineQueueExecutor(
            repository = repo,
            settingsRepository = settingsRepo,
            whisperService = whisperMock,
        )

        repo.insertMeeting(testMeeting)
    }

    @After
    fun tearDown() {
        executor.close()
    }

    @Test
    fun `enqueue adds meeting to processingIds`() {
        executor.enqueue(testMeeting.id)
        assertTrue(testMeeting.id in executor.processingIds.value,
            "Meeting should be in processingIds immediately after enqueue")
    }

    @Test
    fun `processingIds is empty after pipeline completes`() = runBlocking {
        executor.enqueue(testMeeting.id)

        // Wait for the meeting to leave processingIds (pipeline completed or failed)
        withTimeout(10_000L) {
            while (testMeeting.id in executor.processingIds.value) {
                delay(100)
            }
        }

        assertFalse(testMeeting.id in executor.processingIds.value,
            "Meeting must be removed from processingIds after processing")
    }

    @Test
    fun `WhisperService loadModel is not called when model path is blank`() = runBlocking {
        // No model path configured → loadModel should never be called
        executor.enqueue(testMeeting.id)

        withTimeout(10_000L) {
            while (testMeeting.id in executor.processingIds.value) {
                delay(100)
            }
        }

        verify(exactly = 0) { whisperMock.loadModel(any()) }
    }

    @Test
    fun `transcribeLiveChunk does not throw when model is not loaded`() {
        // WhisperService.transcribe() throws when not loaded — this must be caught, not propagate
        every { whisperMock.transcribe(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("Model not loaded. Call loadModel() first.")

        val chunk = com.meetingnotes.audio.RecordingSessionManager.LiveChunk(
            meetingId = testMeeting.id,
            offsetMs = 0L,
            micPcm = ByteArray(3200),  // 100ms of 16kHz 16-bit mono
            sysPcm = ByteArray(3200),
        )

        // Must not throw
        executor.transcribeLiveChunk(chunk)
    }
}
