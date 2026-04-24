package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [DiarizationService.applyDiarization].
 *
 * This is a pure function that requires no subprocess, so all tests run
 * without any mocking or network access.
 */
class DiarizationServiceTest {

    private val service = DiarizationService()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun segment(
        id: String,
        speaker: String,
        startMs: Long,
        endMs: Long,
        text: String = "",
    ) = TranscriptSegment(
        id = id,
        meetingId = "mtg-test",
        speakerLabel = speaker,
        startMs = startMs,
        endMs = endMs,
        text = text,
    )

    private fun dSeg(start: Double, end: Double, speaker: String) =
        DiarizationService.DiarizationSegment(start = start, end = end, speaker = speaker)

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `applyDiarization assigns Caller 1 and Caller 2 to two speakers`() {
        // Two "Caller" transcript segments, each clearly dominated by a different
        // pyannote speaker.
        val transcriptSegments = listOf(
            segment("s1", "Caller", startMs = 0L, endMs = 2000L, text = "Hello"),
            segment("s2", "Caller", startMs = 3000L, endMs = 5000L, text = "Hi back"),
        )
        val diarization = listOf(
            dSeg(0.0, 2.0, "SPEAKER_00"),
            dSeg(3.0, 5.0, "SPEAKER_01"),
        )

        val result = service.applyDiarization(transcriptSegments, diarization)

        assertEquals(2, result.size)
        assertEquals("Caller 1", result[0].speakerLabel, "First segment should be Caller 1")
        assertEquals("Caller 2", result[1].speakerLabel, "Second segment should be Caller 2")
    }

    @Test
    fun `applyDiarization does not modify You segments`() {
        val transcriptSegments = listOf(
            segment("s1", "You", startMs = 0L, endMs = 2000L, text = "I said something"),
            segment("s2", "Caller", startMs = 3000L, endMs = 5000L, text = "Response"),
        )
        val diarization = listOf(
            dSeg(3.0, 5.0, "SPEAKER_00"),
        )

        val result = service.applyDiarization(transcriptSegments, diarization)

        assertEquals(2, result.size)
        // "You" segment must remain unchanged
        assertEquals("You", result[0].speakerLabel, "You segment must not be relabelled")
        assertEquals("s1", result[0].id)
        // Caller segment is updated
        assertEquals("Caller 1", result[1].speakerLabel)
    }

    @Test
    fun `applyDiarization uses max-overlap assignment`() {
        // Transcript segment [1000ms, 4000ms] overlaps with both SPEAKER_00 and
        // SPEAKER_01, but SPEAKER_01 has a larger overlap → should win.
        val transcriptSegments = listOf(
            segment("s1", "Caller", startMs = 1000L, endMs = 4000L, text = "Cross-talk"),
        )
        val diarization = listOf(
            dSeg(0.0, 2.0, "SPEAKER_00"),  // overlap: 1.0s (1.0–2.0)
            dSeg(2.0, 5.0, "SPEAKER_01"),  // overlap: 2.0s (2.0–4.0)
        )

        val result = service.applyDiarization(transcriptSegments, diarization)

        assertEquals(1, result.size)
        assertEquals("Caller 2", result[0].speakerLabel, "SPEAKER_01 (Caller 2) has greater overlap and should win")
    }

    @Test
    fun `applyDiarization handles empty diarization result gracefully`() {
        val transcriptSegments = listOf(
            segment("s1", "Caller", startMs = 0L, endMs = 2000L, text = "Hello"),
            segment("s2", "You", startMs = 2500L, endMs = 4000L, text = "Hi"),
        )

        val result = service.applyDiarization(transcriptSegments, emptyList())

        // No diarization data: all labels must be preserved as-is
        assertEquals(2, result.size)
        assertEquals("Caller", result[0].speakerLabel, "Caller label must be unchanged when diarization is empty")
        assertEquals("You", result[1].speakerLabel, "You label must be unchanged when diarization is empty")
    }

    @Test
    fun `applyDiarization assigns stable speaker indices by first appearance`() {
        // SPEAKER_01 appears in the timeline before SPEAKER_00 (earlier start time).
        // Index assignment must follow temporal first appearance, not lexicographic order:
        //   SPEAKER_01 → Caller 1
        //   SPEAKER_00 → Caller 2
        val transcriptSegments = listOf(
            segment("s1", "Caller", startMs = 0L, endMs = 2000L, text = "First voice"),
            segment("s2", "Caller", startMs = 3000L, endMs = 5000L, text = "Second voice"),
            segment("s3", "Caller", startMs = 6000L, endMs = 8000L, text = "First voice again"),
        )
        val diarization = listOf(
            dSeg(0.0, 2.0, "SPEAKER_01"),  // appears first in timeline
            dSeg(3.0, 5.0, "SPEAKER_00"),  // appears second in timeline
            dSeg(6.0, 8.0, "SPEAKER_01"),  // same as first
        )

        val result = service.applyDiarization(transcriptSegments, diarization)

        assertEquals(3, result.size)
        assertEquals("Caller 1", result[0].speakerLabel, "SPEAKER_01 appears first → Caller 1")
        assertEquals("Caller 2", result[1].speakerLabel, "SPEAKER_00 appears second → Caller 2")
        assertEquals("Caller 1", result[2].speakerLabel, "SPEAKER_01 again → still Caller 1")
    }
}
