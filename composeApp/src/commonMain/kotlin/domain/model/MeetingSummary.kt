package com.meetingnotes.domain.model

import kotlinx.serialization.Serializable

/**
 * An action item extracted from the meeting summary.
 *
 * @param owner Person responsible (extracted from transcript); used as a Logseq [[wiki link]]
 * @param text Description of the action
 * @param dueDate Optional due date string as mentioned in the meeting
 */
@Serializable
data class ActionItem(
    val owner: String? = null,
    val text: String,
    val dueDate: String? = null,
)

/**
 * LLM-generated summary of a meeting.
 *
 * @param meetingId Foreign key to [Meeting.id]
 * @param keyPoints Bulleted list of main discussion points
 * @param decisions Decisions made during the meeting
 * @param actionItems Extracted action items with optional owners for Logseq TODO tasks
 * @param discussionPoints Topics discussed with sub-points
 * @param rawLlmResponse The raw LLM output before parsing (preserved for debugging / fallback)
 */
@Serializable
data class MeetingSummary(
    val meetingId: String,
    val keyPoints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val discussionPoints: List<String> = emptyList(),
    val rawLlmResponse: String? = null,
)
