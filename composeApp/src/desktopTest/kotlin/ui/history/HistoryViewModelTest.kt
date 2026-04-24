package com.meetingnotes.ui.history

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.createInMemoryDatabase
import com.meetingnotes.domain.model.Meeting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [HistoryViewModel].
 *
 * Covers S7-UNIT-03 (reverse chronological order) and S7-UNIT-04 (empty state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private lateinit var repo: MeetingRepository

    @Before
    fun setUp() {
        repo = MeetingRepository(createInMemoryDatabase())
    }

    // ── S7-UNIT-03: Reverse Chronological Order ────────────────────────────────

    @Test
    fun `meetings are presented newest-first`() = runTest(UnconfinedTestDispatcher()) {
        repo.insertMeeting(testMeeting("oldest", startedAt = 1_000L))
        repo.insertMeeting(testMeeting("middle", startedAt = 2_000L))
        repo.insertMeeting(testMeeting("newest", startedAt = 3_000L))

        val vm = HistoryViewModel(repo, this)
        val state = vm.state.first { !it.loading }

        assertEquals(3, state.meetings.size)
        assertEquals("newest", state.meetings[0].id)
        assertEquals("middle", state.meetings[1].id)
        assertEquals("oldest", state.meetings[2].id)
    }

    // ── S7-UNIT-04: Empty State ────────────────────────────────────────────────

    @Test
    fun `empty DB produces empty meetings list with no exception`() = runTest(UnconfinedTestDispatcher()) {
        val vm = HistoryViewModel(repo, this)
        val state = vm.state.first { !it.loading }

        assertTrue(state.meetings.isEmpty(), "Meetings should be empty when DB is empty")
        assertFalse(state.loading, "Loading should be false after load completes")
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    fun `search filters meetings by title case-insensitively`() = runTest(UnconfinedTestDispatcher()) {
        repo.insertMeeting(testMeeting("a", startedAt = 1_000L).copy(title = "Team Standup"))
        repo.insertMeeting(testMeeting("b", startedAt = 2_000L).copy(title = "Product Planning"))
        repo.insertMeeting(testMeeting("c", startedAt = 3_000L).copy(title = "1:1 with Manager"))

        val vm = HistoryViewModel(repo, this)
        vm.state.first { !it.loading }

        vm.search("planning")
        val filtered = vm.state.value

        assertEquals(1, filtered.meetings.size)
        assertEquals("b", filtered.meetings[0].id)
        assertEquals("planning", filtered.query)
    }

    @Test
    fun `search with blank query restores full list`() = runTest(UnconfinedTestDispatcher()) {
        repo.insertMeeting(testMeeting("x", startedAt = 1_000L))
        repo.insertMeeting(testMeeting("y", startedAt = 2_000L))

        val vm = HistoryViewModel(repo, this)
        vm.state.first { !it.loading }

        vm.search("x")
        assertEquals(1, vm.state.value.meetings.size)

        vm.search("")
        assertEquals(2, vm.state.value.meetings.size)
    }

    @Test
    fun `refresh reloads meetings after new ones are inserted`() = runTest(UnconfinedTestDispatcher()) {
        val vm = HistoryViewModel(repo, this)
        vm.state.first { !it.loading } // initial load

        // Insert a meeting after initial load
        repo.insertMeeting(testMeeting("after-init", startedAt = 5_000L))

        vm.refresh()
        val refreshed = vm.state.first { !it.loading && it.meetings.isNotEmpty() }

        assertEquals(1, refreshed.meetings.size)
        assertEquals("after-init", refreshed.meetings[0].id)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun testMeeting(id: String, startedAt: Long) = Meeting(
        id = id,
        title = "Meeting $id",
        startedAt = startedAt,
        audioFilePath = "/tmp/$id.wav",
    )
}
