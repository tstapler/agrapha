package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S3-UNIT-03: diarize() labels mic segments "You" and sys segments "Caller".
 * S3-UNIT-04: isKnownHallucination filters known phrases (via reflection).
 * S3-UNIT-05: Overlap timestamps are preserved — both channels kept.
 */
class DualChannelDiarizerTest {

    private val mockWhisper = mockk<WhisperService>()
    private val diarizer = DualChannelDiarizer(mockWhisper)

    private val micPath: Path = Path.of("/tmp/meeting_mic.wav")
    private val sysPath: Path = Path.of("/tmp/meeting_sys.wav")

    // ── S3-UNIT-03 ────────────────────────────────────────────────────────────

    @Test
    fun `diarize labels mic segments as You and sys segments as Caller`() {
        every {
            mockWhisper.transcribe(micPath.toString(), "mtg-1", "You", any(), any(), any())
        } returns listOf(
            segment("s1", "mtg-1", "You", 0L, 2000L, "Hello"),
        )
        every {
            mockWhisper.transcribe(sysPath.toString(), "mtg-1", "Caller", any(), any(), any())
        } returns listOf(
            segment("s2", "mtg-1", "Caller", 3000L, 5000L, "Hi there"),
        )

        val result = diarizer.diarize(micPath, sysPath, "mtg-1")

        assertEquals(2, result.size)
        assertEquals("You", result.find { it.id == "s1" }?.speakerLabel)
        assertEquals("Caller", result.find { it.id == "s2" }?.speakerLabel)
    }

    @Test
    fun `diarize calls whisperService for both channels`() {
        every { mockWhisper.transcribe(any(), any(), any(), any(), any(), any()) } returns emptyList()

        diarizer.diarize(micPath, sysPath, "mtg-2")

        verify { mockWhisper.transcribe(micPath.toString(), "mtg-2", "You", any(), any(), any()) }
        verify { mockWhisper.transcribe(sysPath.toString(), "mtg-2", "Caller", any(), any(), any()) }
    }

    // ── S3-UNIT-04 ────────────────────────────────────────────────────────────

    @Test
    fun `isKnownHallucination filters common hallucination phrases`() {
        val service = WhisperService()
        val method = WhisperService::class.java.getDeclaredMethod("isKnownHallucination", String::class.java)
        method.isAccessible = true

        val hallucinationPhrases = listOf(
            "Thank you",
            "Thanks",
            "Thank you for watching.",
            "Bye",
            "Uh",
            "Transcribed by https://otter.ai",
        )
        val validPhrases = listOf(
            "Let's discuss the architecture.",
            "We need to refactor this module.",
            "Action item for Alice",
        )

        for (phrase in hallucinationPhrases) {
            assertTrue(
                method.invoke(service, phrase) as Boolean,
                "'$phrase' should be detected as hallucination",
            )
        }
        for (phrase in validPhrases) {
            assertFalse(
                method.invoke(service, phrase) as Boolean,
                "'$phrase' should NOT be detected as hallucination",
            )
        }
    }

    // ── S3-UNIT-05 ────────────────────────────────────────────────────────────

    @Test
    fun `diarize preserves overlapping timestamps from both channels`() {
        // Both speakers active at the same time (crosstalk)
        every {
            mockWhisper.transcribe(micPath.toString(), "mtg-3", "You", any(), any(), any())
        } returns listOf(
            segment("s1", "mtg-3", "You", 1000L, 3000L, "I was saying"),
        )
        every {
            mockWhisper.transcribe(sysPath.toString(), "mtg-3", "Caller", any(), any(), any())
        } returns listOf(
            segment("s2", "mtg-3", "Caller", 1500L, 3500L, "Sorry, go ahead"),
        )

        val result = diarizer.diarize(micPath, sysPath, "mtg-3")

        assertEquals(2, result.size, "Both overlapping segments must be preserved")
        // Result must be sorted by startMs
        assertEquals(1000L, result[0].startMs)
        assertEquals(1500L, result[1].startMs)
    }

    @Test
    fun `diarize returns empty list when both channels produce no segments`() {
        every { mockWhisper.transcribe(any(), any(), any(), any(), any(), any()) } returns emptyList()

        val result = diarizer.diarize(micPath, sysPath, "mtg-4")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `diarize merges and sorts segments chronologically`() {
        every {
            mockWhisper.transcribe(micPath.toString(), "mtg-5", "You", any(), any(), any())
        } returns listOf(
            segment("s3", "mtg-5", "You", 5000L, 7000L, "Third"),
            segment("s1", "mtg-5", "You", 0L, 2000L, "First"),
        )
        every {
            mockWhisper.transcribe(sysPath.toString(), "mtg-5", "Caller", any(), any(), any())
        } returns listOf(
            segment("s2", "mtg-5", "Caller", 3000L, 5000L, "Second"),
        )

        val result = diarizer.diarize(micPath, sysPath, "mtg-5")

        assertEquals(3, result.size)
        assertEquals(0L, result[0].startMs)
        assertEquals(3000L, result[1].startMs)
        assertEquals(5000L, result[2].startMs)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun segment(id: String, meetingId: String, speaker: String, start: Long, end: Long, text: String) =
        TranscriptSegment(id = id, meetingId = meetingId, speakerLabel = speaker, startMs = start, endMs = end, text = text)
}
