package com.meetingnotes.export

import com.meetingnotes.domain.model.ActionItem
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingPlatform
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogseqPageGeneratorTest {

    private val meeting = Meeting(
        id = "m1",
        title = "Sprint Planning",
        startedAt = 1775779200000L,  // 2026-04-10 UTC
        durationSeconds = 2820,      // 47 min
        audioFilePath = "/tmp/m1.wav",
        summaryModel = "ollama/llama3.2",
    )

    private val summary = MeetingSummary(
        meetingId = "m1",
        keyPoints = listOf("Discussed sprint goals", "Agreed on capacity"),
        decisions = listOf("Use SQLDelight for storage"),
        actionItems = listOf(
            ActionItem(owner = "Alice", text = "Write migration runbook", dueDate = "2026-04-17"),
            ActionItem(owner = "Bob", text = "Schedule design review"),
            ActionItem(owner = null, text = "Update CI pipeline"),
        ),
        discussionPoints = listOf("Storage strategy: SQLDelight vs Room"),
    )

    private val segments = listOf(
        TranscriptSegment("s1", "m1", "You", 0L, 3000L, "Let's review the sprint goals."),
        TranscriptSegment("s2", "m1", "Caller", 3500L, 8000L, "I think we have good capacity."),
        TranscriptSegment("s3", "m1", "You", 65000L, 70000L, "Agreed, let's proceed."),
    )

    // ── S5-UNIT-01: Properties syntax ─────────────────────────────────────────

    @Test
    fun `properties block uses Logseq double-colon syntax, not YAML frontmatter`() {
        val page = LogseqPageGenerator.generate(meeting, summary, segments)
        assertContains(page, "date:: [[")
        assertContains(page, "attendees::")
        assertContains(page, "duration::")
        assertFalse(page.startsWith("---"), "Must not use YAML frontmatter")
    }

    // ── S5-UNIT-02: Action items as TODO tasks ────────────────────────────────

    @Test
    fun `action items rendered as Logseq TODO tasks with owner wiki links`() {
        val page = LogseqPageGenerator.generate(meeting, summary, segments)
        assertContains(page, "- TODO [[Alice]]: Write migration runbook — due 2026-04-17")
        assertContains(page, "- TODO [[Bob]]: Schedule design review")
        assertContains(page, "- TODO Update CI pipeline")
    }

    // ── S5-UNIT-03: Attendees as wiki links ───────────────────────────────────

    @Test
    fun `attendees property contains wiki links`() {
        val page = LogseqPageGenerator.generate(meeting, summary, segments)
        assertContains(page, "attendees::")
        assertContains(page, "[[You]]")
        assertContains(page, "[[Alice]]")
        assertContains(page, "[[Bob]]")
    }

    // ── S5-UNIT-04: Transcript collapsed ─────────────────────────────────────

    @Test
    fun `transcript section has collapsed property`() {
        val page = LogseqPageGenerator.generate(meeting, summary, segments)
        assertContains(page, "collapsed:: true")
        assertContains(page, "[00:00] **You**: Let's review the sprint goals.")
        assertContains(page, "[01:05] **You**: Agreed, let's proceed.")
    }

    // ── S5-UNIT-05: Title sanitization ───────────────────────────────────────

    @Test
    fun `sanitizeTitle strips illegal path characters`() {
        val title = "Q2: Planning / Roadmap?"
        val slug = LogseqPageGenerator.sanitizeTitle(title)
        assertFalse(slug.contains(':'))
        assertFalse(slug.contains('/'))
        assertFalse(slug.contains('?'))
    }

    // ── S5-UNIT-06: Namespace path ────────────────────────────────────────────

    @Test
    fun `pagePath returns correct namespace path`() {
        val path = LogseqPageGenerator.pagePath(meeting)
        assertTrue(path.startsWith("logseq/pages/meetings/"), "Must use meetings/ namespace")
        assertTrue(path.endsWith(".md"))
        assertContains(path, "sprint-planning")
        assertContains(path, "2026-04-10")
    }

    // ── S5-UNIT-07: Timestamp format ─────────────────────────────────────────

    @Test
    fun `transcript timestamps formatted as MM_SS`() {
        val page = LogseqPageGenerator.generate(meeting, summary, segments)
        assertContains(page, "[00:00]")   // 0ms
        assertContains(page, "[00:03]")   // 3500ms
        assertContains(page, "[01:05]")   // 65000ms
    }
}
