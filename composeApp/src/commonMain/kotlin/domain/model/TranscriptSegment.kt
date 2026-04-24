package com.meetingnotes.domain.model

import kotlinx.serialization.Serializable

/**
 * A single timestamped speech segment from a meeting transcript.
 *
 * @param id UUID string, primary key
 * @param meetingId Foreign key to [Meeting.id]
 * @param speakerLabel "You" (microphone channel) or "Caller" (system audio channel)
 * @param startMs Start time in milliseconds from recording start
 * @param endMs End time in milliseconds from recording start
 * @param text Transcribed text (already filtered for hallucinations)
 */
@Serializable
data class TranscriptSegment(
    val id: String,
    val meetingId: String,
    val speakerLabel: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
