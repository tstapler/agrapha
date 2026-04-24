package com.meetingnotes.export

import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

actual class LogseqExporter actual constructor() {

    actual fun export(
        meeting: Meeting,
        summary: MeetingSummary,
        segments: List<TranscriptSegment>,
        wikiPath: String,
    ): Pair<String, String> {
        val expandedPath = expandPath(wikiPath)
        val wikiRoot = Path.of(expandedPath)
        require(wikiRoot.exists()) { "Logseq wiki path does not exist: $expandedPath" }
        require(File(expandedPath).isDirectory) { "Logseq wiki path is not a directory: $expandedPath" }

        // ── Write meeting page ─────────────────────────────────────────────────
        val relPagePath = LogseqPageGenerator.pagePath(meeting)
        val pageFile = wikiRoot.resolve(relPagePath)
        pageFile.parent.createDirectories()

        val pageContent = LogseqPageGenerator.generate(meeting, summary, segments)
        // Atomic write: temp → rename (avoids partial file if Logseq is watching)
        val tmpFile = pageFile.resolveSibling(pageFile.fileName.toString() + ".tmp")
        tmpFile.writeText(pageContent, Charsets.UTF_8)
        tmpFile.toFile().renameTo(pageFile.toFile())

        // ── Append journal reference ───────────────────────────────────────────
        val date = LogseqPageGenerator.epochMillisToDate(meeting.startedAt)
        val journalName = date.replace("-", "_")  // e.g. 2026_04_10
        val journalRelPath = "logseq/journals/$journalName.md"
        val journalFile = wikiRoot.resolve(journalRelPath)

        val pageLink = "[[meetings/${LogseqPageGenerator.sanitizeTitle(meeting.title)}/$date]]"
        val journalRef = "- $pageLink #meeting"

        if (journalFile.exists()) {
            val existing = journalFile.readText()
            // Idempotency: only append if reference not already present
            if (!existing.contains(pageLink)) {
                journalFile.writeText(existing.trimEnd() + "\n$journalRef\n", Charsets.UTF_8)
            }
        } else {
            journalFile.parent.createDirectories()
            journalFile.writeText("$journalRef\n", Charsets.UTF_8)
        }

        return relPagePath to journalRelPath
    }

    private fun expandPath(path: String): String {
        var expanded = path
        // Expand leading ~ to home directory
        if (expanded.startsWith("~")) {
            expanded = System.getProperty("user.home") + expanded.substring(1)
        }
        // Expand ${VAR} and $VAR environment variable references
        expanded = expanded.replace(Regex("""\$\{([^}]+)}""")) { System.getenv(it.groupValues[1]) ?: it.value }
        expanded = expanded.replace(Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")) { System.getenv(it.groupValues[1]) ?: it.value }
        return expanded
    }
}
