package com.meetingnotes.export

import com.meetingnotes.domain.model.ActionItem
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingPlatform
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.export.LogseqPageGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S5-INT-01: Meeting page is written to the wiki pages directory.
 * S5-INT-02: Journal entry is appended with a meeting link.
 * S5-INT-03: Export is idempotent — second call doesn't duplicate journal entry.
 * S5-INT-04: Non-existent wiki path throws IllegalArgumentException.
 */
class LogseqExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val exporter = LogseqExporter()

    // Epoch ms for 2026-04-10 00:00:00 UTC  (April 10 2026)
    private val april10EpochMs = 1_744_243_200_000L

    private fun testMeeting(id: String = "mtg-001") = Meeting(
        id = id,
        title = "Architecture Review",
        startedAt = april10EpochMs,
        endedAt = april10EpochMs + 3_600_000L,
        durationSeconds = 3_600L,
        audioFilePath = "/tmp/$id.wav",
        platform = MeetingPlatform.ZOOM,
        summaryModel = "ollama/llama3.2",
    )

    private fun testSummary(meetingId: String = "mtg-001") = MeetingSummary(
        meetingId = meetingId,
        keyPoints = listOf("Discussed KMP architecture", "Decided on CoreML for diarization"),
        decisions = listOf("Use Speaker Diarization CoreML"),
        actionItems = listOf(ActionItem("Alice", "Write ADR", "2026-04-15")),
        discussionPoints = listOf("Trade-offs of on-device vs cloud"),
    )

    private val testSegments = listOf(
        TranscriptSegment("s1", "mtg-001", "You", 0L, 2000L, "Let's start with the architecture review."),
        TranscriptSegment("s2", "mtg-001", "Caller", 2500L, 5000L, "I think we should go native."),
    )

    // ── S5-INT-01 ─────────────────────────────────────────────────────────────

    @Test
    fun `export writes meeting page to wiki pages directory`() {
        val wikiRoot = createMinimalWiki()
        val meeting = testMeeting()

        val (pagePath, _) = exporter.export(meeting, testSummary(), testSegments, wikiRoot.absolutePath)

        val pageFile = File(wikiRoot, pagePath)
        assertTrue(pageFile.exists(), "Meeting page must be created at: $pagePath")
        assertTrue(pageFile.length() > 0, "Meeting page must not be empty")
    }

    @Test
    fun `export page contains key points from summary`() {
        val wikiRoot = createMinimalWiki()

        val (pagePath, _) = exporter.export(testMeeting(), testSummary(), testSegments, wikiRoot.absolutePath)

        val content = File(wikiRoot, pagePath).readText()
        assertTrue(content.contains("KMP architecture"), "Page must contain key points")
    }

    @Test
    fun `export page contains action items`() {
        val wikiRoot = createMinimalWiki()

        val (pagePath, _) = exporter.export(testMeeting(), testSummary(), testSegments, wikiRoot.absolutePath)

        val content = File(wikiRoot, pagePath).readText()
        assertTrue(content.contains("Alice"), "Page must contain action item owner")
    }

    // ── S5-INT-02 ─────────────────────────────────────────────────────────────

    @Test
    fun `export appends meeting link to journal file`() {
        val wikiRoot = createMinimalWiki()
        val meeting = testMeeting()

        // Derive the journal filename exactly as the exporter does, so the test
        // doesn't need to hardcode an epoch-to-date conversion.
        val date = LogseqPageGenerator.epochMillisToDate(meeting.startedAt)
        val journalName = date.replace("-", "_")
        val journalDir = File(wikiRoot, "logseq/journals")
        journalDir.mkdirs()
        val journalFile = File(journalDir, "$journalName.md")
        journalFile.writeText("- Morning standup notes\n")

        exporter.export(meeting, testSummary(), testSegments, wikiRoot.absolutePath)

        val journalContent = journalFile.readText()
        assertTrue(journalContent.contains("#meeting"), "Journal must include #meeting tag")
        assertTrue(journalContent.contains("meetings/"),
            "Journal must include link to meeting page")
    }

    @Test
    fun `export creates journal file if it does not exist`() {
        val wikiRoot = createMinimalWiki()

        val (_, journalPath) = exporter.export(testMeeting(), testSummary(), testSegments, wikiRoot.absolutePath)

        val journalFile = File(wikiRoot, journalPath)
        assertTrue(journalFile.exists(), "Journal file must be created if it didn't exist")
        assertTrue(journalFile.readText().contains("#meeting"))
    }

    // ── S5-INT-03 ─────────────────────────────────────────────────────────────

    @Test
    fun `export is idempotent - second call does not duplicate journal entry`() {
        val wikiRoot = createMinimalWiki()
        val meeting = testMeeting()

        exporter.export(meeting, testSummary(), testSegments, wikiRoot.absolutePath)
        exporter.export(meeting, testSummary(), testSegments, wikiRoot.absolutePath)

        // Find the journal file and count occurrences of #meeting
        val journalDir = File(wikiRoot, "logseq/journals")
        val journalFile = journalDir.listFiles()?.firstOrNull { it.name.endsWith(".md") }
            ?: error("Journal file must exist after export")

        val content = journalFile.readText()
        val occurrences = content.split("#meeting").size - 1
        assertEquals(1, occurrences, "Journal must have exactly one #meeting entry after two identical exports")
    }

    // ── S5-INT-04 ─────────────────────────────────────────────────────────────

    @Test
    fun `export throws IllegalArgumentException for non-existent wiki path`() {
        assertFailsWith<IllegalArgumentException> {
            exporter.export(testMeeting(), testSummary(), testSegments, "/nonexistent/path/that/does/not/exist")
        }
    }

    @Test
    fun `export throws IllegalArgumentException when wiki path is a file not a directory`() {
        val file = tmp.newFile("not-a-directory.txt")

        assertFailsWith<IllegalArgumentException> {
            exporter.export(testMeeting(), testSummary(), testSegments, file.absolutePath)
        }
    }

    // ── S5-INT-05: Path expansion ─────────────────────────────────────────────

    @Test
    fun `export expands tilde to home directory in error message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            exporter.export(testMeeting(), testSummary(), testSegments, "~/does-not-exist-meeting-notes-wiki")
        }
        val home = System.getProperty("user.home")
        assertFalse(ex.message?.contains("~") == true, "Error message must not contain unexpanded ~")
        assertTrue(ex.message?.contains(home) == true, "Error message must contain expanded home path")
    }

    @Test
    fun `export expands dollar HOME env var in error message`() {
        val home = System.getProperty("user.home")
        val ex = assertFailsWith<IllegalArgumentException> {
            exporter.export(testMeeting(), testSummary(), testSegments, "\$HOME/does-not-exist-meeting-notes-wiki")
        }
        assertFalse(ex.message?.contains("\$HOME") == true, "Error message must not contain unexpanded \$HOME")
        assertTrue(ex.message?.contains(home) == true, "Error message must contain expanded home path")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Create a minimal Logseq wiki structure (pages/ and logseq/ dirs). */
    private fun createMinimalWiki(): File {
        val root = tmp.newFolder("wiki")
        File(root, "logseq/pages").mkdirs()
        File(root, "logseq/journals").mkdirs()
        return root
    }
}
