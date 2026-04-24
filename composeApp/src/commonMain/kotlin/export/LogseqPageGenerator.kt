package com.meetingnotes.export

import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment

/**
 * Generates Logseq-compatible Markdown for a meeting.
 *
 * Output format:
 *   - Namespace path: logseq/pages/meetings/[sanitized-title]/[YYYY-MM-DD].md
 *   - Logseq property syntax (::) — NOT YAML frontmatter
 *   - Action items as TODO tasks with [[Owner]] wiki links
 *   - Transcript section is collapsed
 *
 * The generated page is queryable via: {{query (and [[meetings/]] (task TODO))}}
 */
object LogseqPageGenerator {

    /**
     * Returns the relative page path (from the wiki root).
     * E.g. "logseq/pages/meetings/sprint-planning/2026-04-10.md"
     */
    fun pagePath(meeting: Meeting): String {
        val date = epochMillisToDate(meeting.startedAt)
        val slug = sanitizeTitle(meeting.title)
        return "logseq/pages/meetings/$slug/$date.md"
    }

    /** Generate the full page content for [meeting]. */
    fun generate(
        meeting: Meeting,
        summary: MeetingSummary,
        segments: List<TranscriptSegment>,
    ): String = buildString {
        val date = epochMillisToDate(meeting.startedAt)
        val duration = formatDuration(meeting.durationSeconds)

        // ── Properties block (Logseq :: syntax, NOT YAML frontmatter) ─────────
        appendLine("title:: ${meeting.title}")
        appendLine("date:: [[$date]]")

        // Attendees: extract unique owner names + add generic [[You]] and [[Caller]]
        val owners = summary.actionItems.mapNotNull { it.owner }.distinct()
        val attendees = (listOf("You") + owners).joinToString(", ") { "[[${it}]]" }
        appendLine("attendees:: $attendees")
        appendLine("type:: [[sync]]")
        appendLine("duration:: $duration")
        meeting.summaryModel?.let { appendLine("summary-model:: $it") }
        appendLine()

        // ── Summary section ────────────────────────────────────────────────────
        if (summary.keyPoints.isNotEmpty()) {
            appendLine("## Summary")
            appendLine()
            for (point in summary.keyPoints) appendLine("- $point")
            appendLine()
        }

        // ── Key Decisions ──────────────────────────────────────────────────────
        if (summary.decisions.isNotEmpty()) {
            appendLine("## Key Decisions")
            appendLine()
            for (decision in summary.decisions) appendLine("- $decision")
            appendLine()
        }

        // ── Action Items (Logseq TODO tasks with [[Owner]] links) ──────────────
        if (summary.actionItems.isNotEmpty()) {
            appendLine("## Action Items")
            appendLine()
            for (item in summary.actionItems) {
                val ownerPart = item.owner?.let { "[[${it}]]: " } ?: ""
                val duePart = item.dueDate?.let { " — due $it" } ?: ""
                appendLine("- TODO ${ownerPart}${item.text}${duePart}")
            }
            appendLine()
        }

        // ── Discussion Points ──────────────────────────────────────────────────
        if (summary.discussionPoints.isNotEmpty()) {
            appendLine("## Discussion Points")
            appendLine()
            for (point in summary.discussionPoints) appendLine("- $point")
            appendLine()
        }

        // ── Full Transcript (collapsed) ────────────────────────────────────────
        if (segments.isNotEmpty()) {
            appendLine("## Full Transcript")
            appendLine("collapsed:: true")
            appendLine()
            for (seg in segments.sortedBy { it.startMs }) {
                val ts = formatTimestamp(seg.startMs)
                val speaker = seg.speakerLabel ?: "Unknown"
                appendLine("- [$ts] **$speaker**: ${seg.text}")
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Remove characters illegal in Logseq namespace paths and file names. */
    internal fun sanitizeTitle(title: String): String =
        title
            .lowercase()
            .replace(Regex("[:/\\\\?*\"<>|]"), "")   // illegal path chars
            .replace(Regex("[^a-z0-9\\-_ ]"), "")     // keep alphanumeric, dash, underscore, space
            .trim()
            .replace(Regex("\\s+"), "-")               // spaces → dashes
            .take(80)                                   // max path segment length

    /** Format epoch millis as YYYY-MM-DD. */
    internal fun epochMillisToDate(epochMillis: Long): String {
        val seconds = epochMillis / 1000
        val days = seconds / 86400
        // Simplified Gregorian calculation (accurate for 2000–2100)
        var n = days + 719468
        val era = n / 146097
        val doe = n - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = y + (if (m <= 2) 1 else 0)
        return "%04d-%02d-%02d".format(year, m, d)
    }

    private fun formatDuration(seconds: Long?): String {
        if (seconds == null) return "unknown"
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    private fun formatTimestamp(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
