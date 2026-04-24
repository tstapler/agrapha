package com.meetingnotes.domain

import com.meetingnotes.domain.llm.SummaryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryParserTest {

    private val meetingId = "test-meeting-1"

    // ── S4-UNIT-01: Clean JSON ────────────────────────────────────────────────

    @Test
    fun `parses clean JSON response`() {
        val json = """
            {
              "keyPoints": ["Point A", "Point B"],
              "decisions": ["Use SQLDelight"],
              "actionItems": [
                {"owner": "Alice", "text": "Write spec", "dueDate": "2026-04-10"},
                {"owner": null, "text": "Schedule follow-up", "dueDate": null}
              ],
              "discussionPoints": ["Topic 1: detail"]
            }
        """.trimIndent()

        val result = SummaryParser.parse(meetingId, json)

        assertEquals(meetingId, result.meetingId)
        assertEquals(listOf("Point A", "Point B"), result.keyPoints)
        assertEquals(listOf("Use SQLDelight"), result.decisions)
        assertEquals(2, result.actionItems.size)
        assertEquals("Alice", result.actionItems[0].owner)
        assertEquals("Write spec", result.actionItems[0].text)
        assertEquals("2026-04-10", result.actionItems[0].dueDate)
        assertNull(result.actionItems[1].owner)
        assertEquals(listOf("Topic 1: detail"), result.discussionPoints)
    }

    // ── S4-UNIT-02: Code-fenced JSON ──────────────────────────────────────────

    @Test
    fun `parses JSON wrapped in triple-backtick json fence`() {
        val fenced = "```json\n{\"keyPoints\":[\"Point A\"],\"decisions\":[],\"actionItems\":[],\"discussionPoints\":[]}\n```"
        val result = SummaryParser.parse(meetingId, fenced)
        assertEquals(listOf("Point A"), result.keyPoints)
    }

    @Test
    fun `parses JSON wrapped in plain triple-backtick fence`() {
        val fenced = "```\n{\"keyPoints\":[],\"decisions\":[\"Go with KMP\"],\"actionItems\":[],\"discussionPoints\":[]}\n```"
        val result = SummaryParser.parse(meetingId, fenced)
        assertEquals(listOf("Go with KMP"), result.decisions)
    }

    // ── S4-UNIT-03: Malformed JSON fallback ───────────────────────────────────

    @Test
    fun `falls back to rawLlmResponse on malformed JSON`() {
        val malformed = """{"keyPoints": ["one""""  // unclosed
        val result = SummaryParser.parse(meetingId, malformed)
        assertNotNull(result.rawLlmResponse)
        assertTrue(result.keyPoints.isEmpty())
    }

    // ── S4-UNIT-04: Narrative text fallback ───────────────────────────────────

    @Test
    fun `falls back to rawLlmResponse on plain prose`() {
        val prose = "The team discussed the migration plan and agreed to proceed with SQLDelight."
        val result = SummaryParser.parse(meetingId, prose)
        assertEquals(prose, result.rawLlmResponse)
        assertTrue(result.keyPoints.isEmpty())
        assertTrue(result.decisions.isEmpty())
        assertTrue(result.actionItems.isEmpty())
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `extracts JSON embedded in prose`() {
        val withPreamble = """
            Here is the summary you requested:
            {"keyPoints":["Agreed on architecture"],"decisions":[],"actionItems":[],"discussionPoints":[]}
            Let me know if you need changes.
        """.trimIndent()
        val result = SummaryParser.parse(meetingId, withPreamble)
        assertEquals(listOf("Agreed on architecture"), result.keyPoints)
    }
}
