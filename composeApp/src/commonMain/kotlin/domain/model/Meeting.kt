package com.meetingnotes.domain.model

import kotlinx.serialization.Serializable

/** The platform the meeting was conducted on. */
@Serializable
enum class MeetingPlatform {
    ZOOM, TEAMS, MEET, UNKNOWN
}

/**
 * A recorded meeting session.
 *
 * @param id UUID string, primary key
 * @param title Human-readable title (defaulting to date/time if not set by user)
 * @param startedAt Epoch milliseconds when recording started
 * @param endedAt Epoch milliseconds when recording stopped (null while recording)
 * @param durationSeconds Computed duration; null until recording stops
 * @param audioFilePath Absolute path to the dual-channel WAV file
 * @param transcriptFilePath Absolute path to the JSON transcript file (null until transcribed)
 * @param platform Detected meeting platform
 * @param summaryModel Display name of the LLM used for summarisation (e.g. "ollama/llama3.2")
 * @param audioDurationMs Length of the audio recording in milliseconds
 * @param transcriptionDurationMs Wall-clock time Whisper took to transcribe, in milliseconds
 * @param transcriptionMetricsJson JSON-encoded [TranscriptionMetrics] with per-stage breakdown
 */
@Serializable
data class Meeting(
    val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationSeconds: Long? = null,
    val audioFilePath: String,
    val transcriptFilePath: String? = null,
    val platform: MeetingPlatform = MeetingPlatform.UNKNOWN,
    val summaryModel: String? = null,
    val audioDurationMs: Long? = null,
    val transcriptionDurationMs: Long? = null,
    val transcriptionMetricsJson: String? = null,
)
