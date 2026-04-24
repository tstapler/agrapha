package com.meetingnotes.export

import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment

/** Platform-specific writer for Logseq files. */
expect class LogseqExporter() {

    /**
     * Write the meeting page and append a journal reference.
     *
     * @param meeting The completed meeting
     * @param summary LLM-generated summary
     * @param segments Transcript segments
     * @param wikiPath Absolute path to the Logseq wiki root
     * @return Pair of (pagePath, journalPath) — both relative to wikiPath
     * @throws IllegalArgumentException if wikiPath does not exist or is not a directory
     * @throws java.io.IOException on write failure
     */
    fun export(
        meeting: Meeting,
        summary: MeetingSummary,
        segments: List<TranscriptSegment>,
        wikiPath: String,
    ): Pair<String, String>
}
