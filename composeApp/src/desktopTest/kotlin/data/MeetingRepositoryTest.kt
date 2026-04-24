package com.meetingnotes.data

import com.meetingnotes.db.MeetingDatabase
import com.meetingnotes.domain.model.ActionItem
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repository unit tests using in-memory SQLite.
 * Platform actual (DesktopDatabaseFactory) provides the JdbcSqliteDriver.
 */
class MeetingRepositoryTest {

    private lateinit var repo: MeetingRepository

    @BeforeTest
    fun setUp() {
        val db = createInMemoryDatabase()
        repo = MeetingRepository(db)
    }

    // ── S2-UNIT-03: Insert + query round-trip ─────────────────────────────────

    @Test
    fun `insertMeeting and getMeetingById round-trip`() {
        val meeting = testMeeting("m1")
        repo.insertMeeting(meeting)

        val loaded = repo.getMeetingById("m1")
        assertNotNull(loaded)
        assertEquals(meeting.id, loaded.id)
        assertEquals(meeting.title, loaded.title)
        assertEquals(meeting.startedAt, loaded.startedAt)
        assertEquals(meeting.audioFilePath, loaded.audioFilePath)
    }

    @Test
    fun `inserts segments linked to meeting`() {
        repo.insertMeeting(testMeeting("m2"))
        val segments = listOf(
            TranscriptSegment("s1", "m2", "You", 0L, 2000L, "Hello"),
            TranscriptSegment("s2", "m2", "Caller", 2500L, 5000L, "Hi there"),
            TranscriptSegment("s3", "m2", "You", 6000L, 9000L, "Great"),
        )
        segments.forEach { repo.insertSegment(it) }

        val loaded = repo.getSegmentsByMeetingId("m2")
        assertEquals(3, loaded.size)
        assertEquals("s1", loaded[0].id)
        assertEquals("s3", loaded[2].id)
    }

    @Test
    fun `inserts and retrieves summary with all fields`() {
        repo.insertMeeting(testMeeting("m3"))
        val summary = MeetingSummary(
            meetingId = "m3",
            keyPoints = listOf("Point A", "Point B"),
            decisions = listOf("Use KMP"),
            actionItems = listOf(ActionItem("Alice", "Write spec", "2026-04-10")),
            discussionPoints = listOf("Architecture choices"),
        )
        repo.insertSummary(summary)

        val loaded = repo.getSummaryByMeetingId("m3")
        assertNotNull(loaded)
        assertEquals(listOf("Point A", "Point B"), loaded.keyPoints)
        assertEquals(listOf("Use KMP"), loaded.decisions)
        assertEquals(1, loaded.actionItems.size)
        assertEquals("Alice", loaded.actionItems[0].owner)
    }

    // ── S2-UNIT-04: getAllMeetings sort order ─────────────────────────────────

    @Test
    fun `getAllMeetings returns newest first`() {
        repo.insertMeeting(testMeeting("old", startedAt = 1000L))
        repo.insertMeeting(testMeeting("mid", startedAt = 2000L))
        repo.insertMeeting(testMeeting("new", startedAt = 3000L))

        val all = repo.getAllMeetings()
        assertEquals(3, all.size)
        assertEquals("new", all[0].id)
        assertEquals("old", all[2].id)
    }

    // ── updateMeetingTitle ────────────────────────────────────────────────────

    @Test
    fun `updateMeetingTitle persists new title`() {
        repo.insertMeeting(testMeeting("m4"))
        repo.updateMeetingTitle("m4", "Renamed Meeting")

        val loaded = repo.getMeetingById("m4")
        assertNotNull(loaded)
        assertEquals("Renamed Meeting", loaded.title)
    }

    // ── deleteMeeting ─────────────────────────────────────────────────────────

    @Test
    fun `deleteMeeting removes the meeting row`() {
        repo.insertMeeting(testMeeting("m5"))
        assertNotNull(repo.getMeetingById("m5"), "Meeting must exist before deletion")

        repo.deleteMeeting("m5")

        assertNull(repo.getMeetingById("m5"), "Meeting must be gone after deletion")
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `getMeetingById returns null for unknown id`() {
        assertNull(repo.getMeetingById("nonexistent"))
    }

    @Test
    fun `getSegmentsByMeetingId returns empty list for unknown meeting`() {
        assertTrue(repo.getSegmentsByMeetingId("ghost").isEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun testMeeting(id: String, startedAt: Long = 1000L) = Meeting(
        id = id,
        title = "Test Meeting $id",
        startedAt = startedAt,
        audioFilePath = "/tmp/$id.wav",
    )
}
