package com.meetingnotes.domain

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.export.LogseqExporter

/**
 * Exports a completed meeting to the Logseq wiki.
 *
 * Prerequisites:
 * - A transcript (segments) must exist for the meeting
 * - A summary must exist for the meeting
 * - [SettingsRepository] must have a non-blank [logseqWikiPath]
 *
 * @return [Result.success] with a (pagePath, journalPath) pair on success
 * @return [Result.failure] with a descriptive exception on any error
 */
class ExportToLogseqUseCase(
    private val exporter: LogseqExporter,
    private val repository: MeetingRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun execute(meetingId: String): Result<Pair<String, String>> = runCatching {
        val meeting = repository.getMeetingById(meetingId)
            ?: error("Meeting '$meetingId' not found")
        val summary = repository.getSummaryByMeetingId(meetingId)
            ?: error("No summary for meeting '$meetingId' — run summarization first")
        val segments = repository.getSegmentsByMeetingId(meetingId)
        val settings = settingsRepository.load()
        require(settings.logseqWikiPath.isNotBlank()) {
            "Logseq wiki path is not configured — open Settings and set the wiki path"
        }
        exporter.export(meeting, summary, segments, settings.logseqWikiPath)
    }
}
