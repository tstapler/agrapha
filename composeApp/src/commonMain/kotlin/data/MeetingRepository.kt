package com.meetingnotes.data

import com.meetingnotes.db.MeetingDatabase
import com.meetingnotes.domain.model.ActionItem
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingPlatform
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Data access layer for meetings, transcript segments, and summaries.
 *
 * All methods are synchronous and should be called from a coroutine with
 * Dispatchers.IO to avoid blocking the main thread.
 */
class MeetingRepository(private val db: MeetingDatabase) {

    // ── Meeting ───────────────────────────────────────────────────────────────

    fun insertMeeting(meeting: Meeting) {
        db.meetingQueries.insertMeeting(
            id = meeting.id,
            title = meeting.title,
            startedAt = meeting.startedAt,
            endedAt = meeting.endedAt,
            durationSeconds = meeting.durationSeconds,
            audioFilePath = meeting.audioFilePath,
            transcriptFilePath = meeting.transcriptFilePath,
            platform = meeting.platform.name,
            summaryModel = meeting.summaryModel,
            audioDurationMs = meeting.audioDurationMs,
            transcriptionDurationMs = meeting.transcriptionDurationMs,
        )
    }

    fun getMeetingById(id: String): Meeting? =
        db.meetingQueries.getMeetingById(id).executeAsOneOrNull()?.toDomain()

    fun getAllMeetings(): List<Meeting> =
        db.meetingQueries.getAllMeetings().executeAsList().map { it.toDomain() }

    fun getRecentMeetings(limit: Int): List<Meeting> =
        db.meetingQueries.getRecentMeetings(limit.toLong()).executeAsList().map { it.toDomain() }

    fun updateMeetingEnd(id: String, endedAt: Long, durationSeconds: Long) {
        db.meetingQueries.updateMeetingEnd(endedAt, durationSeconds, id)
    }

    fun updateMeetingTranscript(id: String, transcriptFilePath: String) {
        db.meetingQueries.updateMeetingTranscript(transcriptFilePath, id)
    }

    fun updateMeetingSummaryModel(id: String, summaryModel: String) {
        db.meetingQueries.updateMeetingSummaryModel(summaryModel, id)
    }

    fun updateMeetingTimings(id: String, audioDurationMs: Long?, transcriptionDurationMs: Long?) {
        db.meetingQueries.updateMeetingTimings(audioDurationMs, transcriptionDurationMs, id)
    }

    fun getMeetingsBefore(cutoffMs: Long): List<Meeting> =
        db.meetingQueries.getMeetingsBefore(cutoffMs).executeAsList().map { it.toDomain() }

    fun deleteMeeting(id: String) {
        db.meetingQueries.deleteMeeting(id)
    }

    fun updateMeetingTitle(id: String, title: String) {
        db.meetingQueries.updateMeetingTitle(title, id)
    }

    fun updateMeetingMetrics(id: String, metricsJson: String) {
        db.meetingQueries.updateMeetingMetrics(metricsJson, id)
    }

    /** Mark the audio file as deleted by clearing its path (file has been removed from disk). */
    fun clearAudioFilePath(id: String) {
        db.meetingQueries.clearAudioFilePath(id)
    }

    // ── TranscriptSegment ─────────────────────────────────────────────────────

    fun insertSegment(segment: TranscriptSegment) {
        db.meetingQueries.insertSegment(
            id = segment.id,
            meetingId = segment.meetingId,
            speakerLabel = segment.speakerLabel,
            startMs = segment.startMs,
            endMs = segment.endMs,
            text = segment.text,
        )
    }

    fun deleteSegmentsByMeetingId(meetingId: String) {
        db.meetingQueries.deleteSegmentsByMeetingId(meetingId)
    }

    fun getSegmentsByMeetingId(meetingId: String): List<TranscriptSegment> =
        db.meetingQueries.getSegmentsByMeetingId(meetingId).executeAsList().map { row ->
            TranscriptSegment(
                id = row.id,
                meetingId = row.meetingId,
                speakerLabel = row.speakerLabel,
                startMs = row.startMs,
                endMs = row.endMs,
                text = row.text,
            )
        }

    // ── MeetingSummary ────────────────────────────────────────────────────────

    fun insertSummary(summary: MeetingSummary) {
        db.meetingQueries.insertSummary(
            meetingId = summary.meetingId,
            keyPointsJson = Json.encodeToString(summary.keyPoints),
            decisionsJson = Json.encodeToString(summary.decisions),
            actionItemsJson = Json.encodeToString(summary.actionItems),
            discussionPointsJson = Json.encodeToString(summary.discussionPoints),
            rawLlmResponse = summary.rawLlmResponse,
        )
    }

    fun getSummaryByMeetingId(meetingId: String): MeetingSummary? =
        db.meetingQueries.getSummaryByMeetingId(meetingId).executeAsOneOrNull()?.let { row ->
            MeetingSummary(
                meetingId = row.meetingId,
                keyPoints = Json.decodeFromString(row.keyPointsJson),
                decisions = Json.decodeFromString(row.decisionsJson),
                actionItems = Json.decodeFromString(row.actionItemsJson),
                discussionPoints = Json.decodeFromString(row.discussionPointsJson),
                rawLlmResponse = row.rawLlmResponse,
            )
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun meeting.Meeting.toDomain() = Meeting(
        id = id,
        title = title,
        startedAt = startedAt,
        endedAt = endedAt,
        durationSeconds = durationSeconds,
        audioFilePath = audioFilePath,
        transcriptFilePath = transcriptFilePath,
        platform = runCatching { MeetingPlatform.valueOf(platform) }.getOrDefault(MeetingPlatform.UNKNOWN),
        summaryModel = summaryModel,
        audioDurationMs = audioDurationMs,
        transcriptionDurationMs = transcriptionDurationMs,
        transcriptionMetricsJson = transcriptionMetricsJson,
    )
}
