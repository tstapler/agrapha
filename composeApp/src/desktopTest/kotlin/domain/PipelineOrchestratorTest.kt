package com.meetingnotes.domain

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.createInMemoryDatabase
import com.meetingnotes.domain.llm.LlmProvider
import com.meetingnotes.domain.llm.LlmUnavailableException
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.RecordingState
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.export.LogseqExporter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [PipelineOrchestrator].
 *
 * Covers S7-UNIT-01 (partial save on stage failure) and S7-UNIT-02 (retry skips completed stages).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineOrchestratorTest {

    private lateinit var repo: MeetingRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var transcriptionMock: TranscriptionUseCase
    private lateinit var llmMock: LlmProvider
    private lateinit var exporterMock: LogseqExporter
    private lateinit var stateFlow: MutableStateFlow<RecordingState>
    private lateinit var orchestrator: PipelineOrchestrator

    private val testMeeting = Meeting(
        id = "test-meeting-001",
        title = "Test Meeting",
        startedAt = 1_000_000L,
        audioFilePath = "/tmp/test-meeting.wav",
    )

    @Before
    fun setUp() {
        repo = MeetingRepository(createInMemoryDatabase())
        settingsRepo = SettingsRepository(createInMemoryDatabase())

        transcriptionMock = mockk<TranscriptionUseCase>()
        llmMock = mockk<LlmProvider>()
        exporterMock = mockk<LogseqExporter>()
        stateFlow = MutableStateFlow(RecordingState.Idle)

        orchestrator = PipelineOrchestrator(
            transcriptionUseCase = transcriptionMock,
            llmProvider = llmMock,
            exporter = exporterMock,
            repository = repo,
            settingsRepository = settingsRepo,
            stateFlow = stateFlow,
        )

        // Pre-insert meeting so repo has it
        repo.insertMeeting(testMeeting)
    }

    // ── S7-UNIT-01: Partial Save on Stage Failure ─────────────────────────────

    @Test
    fun `transcription segments are preserved when summarization fails`() = runTest {
        // Pre-insert segments (simulates transcription already having written to DB)
        val segment = TranscriptSegment(
            id = "seg-001",
            meetingId = testMeeting.id,
            speakerLabel = "You",
            startMs = 0L,
            endMs = 2000L,
            text = "Hello from the test",
        )
        repo.insertSegment(segment)

        // Mock transcription to "succeed" (just emit progress — doesn't write to DB)
        every { transcriptionMock.transcribe(any(), any(), any()) } returns flow {
            emit(TranscriptionProgress("Transcribing", 50))
            emit(TranscriptionProgress("Complete", 100))
        }

        // Mock LLM to throw on summarization
        coEvery { llmMock.summarize(any(), any()) } throws RuntimeException("LLM offline")

        orchestrator.run(testMeeting, "/tmp/model.bin")

        // Segments must NOT have been rolled back
        val segments = repo.getSegmentsByMeetingId(testMeeting.id)
        assertFalse(segments.isEmpty(), "Transcript segments must survive a summarization failure")
        assertEquals("seg-001", segments.first().id)

        // State must reflect the failure at summarization
        val errorState = stateFlow.value
        assertIs<RecordingState.Error>(errorState)
        assertEquals("Summarization", (errorState as RecordingState.Error).stage)
    }

    // ── S7-UNIT-02: Retry Resumes from Failed Stage ───────────────────────────

    @Test
    fun `retry does not re-run transcription when summarization had failed`() = runTest {
        // First run: transcription succeeds, summarization fails
        every { transcriptionMock.transcribe(any(), any(), any()) } returns flow {
            emit(TranscriptionProgress("Complete", 100))
        }
        coEvery { llmMock.summarize(any(), any()) } throws RuntimeException("First failure")

        orchestrator.run(testMeeting, "/tmp/model.bin")

        assertIs<RecordingState.Error>(stateFlow.value)

        // Second run (retry): summarization now succeeds, export succeeds
        val fakeSummary = MeetingSummary(
            meetingId = testMeeting.id,
            keyPoints = listOf("Key point A"),
        )
        coEvery { llmMock.summarize(any(), any()) } returns fakeSummary
        every { exporterMock.export(any(), any(), any(), any()) } returns
            ("logseq/pages/test.md" to "logseq/journals/2026_01_01.md")

        // Provide a valid wiki path in settings for export stage
        settingsRepo.save(AppSettings(logseqWikiPath = "/tmp"))

        orchestrator.retry("/tmp/model.bin")

        // Transcription must NOT have been called a second time
        verify(exactly = 1) { transcriptionMock.transcribe(any(), any(), any()) }
    }

    @Test
    fun `retry from export failure does not re-run transcription or summarization`() = runTest {
        // Pre-insert segments and summary so stages 0 and 1 are "already done"
        repo.insertSegment(TranscriptSegment("s1", testMeeting.id, "You", 0L, 1000L, "hello"))
        val summary = MeetingSummary(testMeeting.id, keyPoints = listOf("point"))
        repo.insertSummary(summary)

        // Mock stages to all succeed, except export fails first
        every { transcriptionMock.transcribe(any(), any(), any()) } returns flow {
            emit(TranscriptionProgress("Complete", 100))
        }
        coEvery { llmMock.summarize(any(), any()) } returns summary
        every { exporterMock.export(any(), any(), any(), any()) } throws RuntimeException("Export failed")
        settingsRepo.save(AppSettings(logseqWikiPath = "/tmp"))

        orchestrator.run(testMeeting, "/tmp/model.bin")

        assertIs<RecordingState.Error>(stateFlow.value)
        assertEquals("Export", (stateFlow.value as RecordingState.Error).stage)

        // Now retry — export succeeds
        every { exporterMock.export(any(), any(), any(), any()) } returns
            ("logseq/pages/test.md" to "logseq/journals/2026_01_01.md")

        orchestrator.retry("/tmp/model.bin")

        // Transcription and summarization must NOT be re-called
        verify(exactly = 1) { transcriptionMock.transcribe(any(), any(), any()) }
        coVerify(exactly = 1) { llmMock.summarize(any(), any()) }
    }

    // ── S7-UNIT-03: LlmUnavailableException soft-skip ─────────────────────────

    @Test
    fun `LlmUnavailableException causes soft-skip to Complete with summarySkipped=true`() = runTest {
        every { transcriptionMock.transcribe(any(), any(), any()) } returns flow {
            emit(TranscriptionProgress("Complete", 100))
        }
        coEvery { llmMock.summarize(any(), any()) } throws LlmUnavailableException("Ollama not running")

        orchestrator.run(testMeeting, "/tmp/model.bin")

        val state = stateFlow.value
        assertIs<RecordingState.Complete>(state)
        assertTrue(state.summarySkipped, "summarySkipped must be true when LLM was unavailable")
        // No error state — pipeline should complete gracefully
    }

    // ── S7-UNIT-04: updateMeetingTimings persisted after Stage 0 ──────────────

    @Test
    fun `transcriptionDurationMs is persisted after successful transcription`() = runTest {
        every { transcriptionMock.transcribe(any(), any(), any()) } returns flow {
            emit(TranscriptionProgress("Complete", 100))
        }
        val fakeSummary = MeetingSummary(meetingId = testMeeting.id, keyPoints = listOf("point"))
        coEvery { llmMock.summarize(any(), any()) } returns fakeSummary
        every { exporterMock.export(any(), any(), any(), any()) } returns
            ("logseq/pages/test.md" to "logseq/journals/2026_01_01.md")
        settingsRepo.save(AppSettings(logseqWikiPath = "/tmp"))

        orchestrator.run(testMeeting, "/tmp/model.bin")

        assertIs<RecordingState.Complete>(stateFlow.value)
        val updated = repo.getMeetingById(testMeeting.id)
        assertNotNull(updated?.transcriptionDurationMs, "transcriptionDurationMs must be persisted after Stage 0")
    }
}
