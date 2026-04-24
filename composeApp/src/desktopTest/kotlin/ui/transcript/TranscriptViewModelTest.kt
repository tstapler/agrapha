package com.meetingnotes.ui.transcript

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.createInMemoryDatabase
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Tests for [TranscriptViewModel].
 *
 * Covers S7-UNIT-05 (segments re-sorted by startMs).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptViewModelTest {

    private lateinit var repo: MeetingRepository
    private lateinit var settingsRepo: SettingsRepository

    private val meetingId = "vm-test-meeting"

    @Before
    fun setUp() {
        repo = MeetingRepository(createInMemoryDatabase())
        settingsRepo = SettingsRepository(createInMemoryDatabase())

        repo.insertMeeting(
            Meeting(
                id = meetingId,
                title = "ViewModel Test Meeting",
                startedAt = 1_000_000L,
                audioFilePath = "/tmp/$meetingId.wav",
            )
        )
    }

    // ── S7-UNIT-05: Segment Order ──────────────────────────────────────────────

    @Test
    fun `segments are sorted by startMs regardless of insertion order`() = runTest(UnconfinedTestDispatcher()) {
        // Insert out-of-order
        repo.insertSegment(TranscriptSegment("s3", meetingId, "You", 8_000L, 10_000L, "Third"))
        repo.insertSegment(TranscriptSegment("s1", meetingId, "Caller", 0L, 2_000L, "First"))
        repo.insertSegment(TranscriptSegment("s2", meetingId, "You", 4_000L, 6_000L, "Second"))

        val vm = TranscriptViewModel(meetingId, repo, settingsRepo, this)
        val state = vm.state.first { !it.loading }

        assertEquals(3, state.segments.size)
        assertEquals("s1", state.segments[0].id, "Segment with startMs=0 must be first")
        assertEquals("s2", state.segments[1].id, "Segment with startMs=4000 must be second")
        assertEquals("s3", state.segments[2].id, "Segment with startMs=8000 must be third")

        // Verify timestamps are monotonically non-decreasing
        val times = state.segments.map { it.startMs }
        assertEquals(times.sorted(), times, "Segments must be in ascending startMs order")
    }

    @Test
    fun `empty segments list loads without exception`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TranscriptViewModel(meetingId, repo, settingsRepo, this)
        val state = vm.state.first { !it.loading }

        assertEquals(meetingId, state.meeting?.id)
        assertEquals(emptyList(), state.segments)
        assertFalse(state.loading)
    }

    @Test
    fun `copyTranscript formats segments as timestamped speaker lines`() = runTest(UnconfinedTestDispatcher()) {
        repo.insertSegment(TranscriptSegment("s1", meetingId, "You", 65_000L, 70_000L, "Hello world"))
        repo.insertSegment(TranscriptSegment("s2", meetingId, "Caller", 125_000L, 130_000L, "How are you"))

        val vm = TranscriptViewModel(meetingId, repo, settingsRepo, this)
        vm.state.first { !it.loading }

        val transcript = vm.copyTranscript()
        val lines = transcript.lines()

        assertEquals(2, lines.size)
        assertEquals("[01:05] You: Hello world", lines[0])
        assertEquals("[02:05] Caller: How are you", lines[1])
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Test
    fun `rename updates meeting title in DB and state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TranscriptViewModel(meetingId, repo, settingsRepo, this)
        vm.state.first { !it.loading }

        vm.startEditTitle()
        vm.onTitleDraftChange("New Title")
        vm.confirmRename()

        val state = vm.state.first { it.meeting?.title == "New Title" }
        assertEquals("New Title", state.meeting?.title)
        assertEquals("New Title", repo.getMeetingById(meetingId)?.title)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes meeting from DB and sets deleted flag`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TranscriptViewModel(meetingId, repo, settingsRepo, this)
        vm.state.first { !it.loading }

        vm.delete()

        val state = vm.state.first { it.deleted }
        assertEquals(true, state.deleted)
        assertNull(repo.getMeetingById(meetingId))
    }

    @Test
    fun `summary is loaded alongside meeting and segments`() = runTest(UnconfinedTestDispatcher()) {
        repo.insertSummary(
            MeetingSummary(
                meetingId = meetingId,
                keyPoints = listOf("Point A", "Point B"),
                decisions = listOf("Proceed with KMP"),
            )
        )

        val vm = TranscriptViewModel(meetingId, repo, settingsRepo, this)
        val state = vm.state.first { !it.loading }

        assertEquals(listOf("Point A", "Point B"), state.summary?.keyPoints)
        assertEquals(listOf("Proceed with KMP"), state.summary?.decisions)
    }
}
