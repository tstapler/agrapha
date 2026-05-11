package com.meetingnotes.data.audio

import com.meetingnotes.domain.audio.DiarizationBackend
import com.meetingnotes.domain.audio.DiarizationSegment
import com.meetingnotes.domain.model.TranscriptSegment
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the [DiarizationBackend] interface contract using a minimal mock implementation.
 * Ensures all methods are callable and return correct types.
 */
class DiarizationBackendContractTest {

    private val mock = MockDiarizationBackend()

    @Test
    fun `isAvailable is callable and returns Boolean`() = runBlocking {
        assertTrue(mock.isAvailable())
    }

    @Test
    fun `areModelsAvailable is callable and returns Boolean`() = runBlocking {
        assertTrue(mock.areModelsAvailable())
    }

    @Test
    fun `downloadModels is callable without exception`() = runBlocking {
        mock.downloadModels()
    }

    @Test
    fun `diarize is callable and returns list`() = runBlocking {
        val result = mock.diarize("/fake/path.wav", "hf_token")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `applyDiarization default impl preserves You segments`() {
        val segments = listOf(
            seg("You", 0, 2000),
            seg("Caller", 2000, 4000),
        )
        val diarization = listOf(DiarizationSegment(2.0, 4.0, "SPEAKER_00"))

        val result = mock.applyDiarization(segments, diarization)

        assertEquals("You", result[0].speakerLabel)
        assertEquals("Caller 1", result[1].speakerLabel)
    }

    @Test
    fun `applyDiarization default impl returns segments unchanged when diarization is empty`() {
        val segments = listOf(seg("Caller", 0, 1000))
        val result = mock.applyDiarization(segments, emptyList())
        assertEquals(segments, result)
    }

    @Test
    fun `applyDiarization default impl assigns speaker indices in first-appearance order`() {
        val segments = listOf(
            seg("Caller", 0, 2000),
            seg("Caller", 2000, 4000),
            seg("Caller", 4000, 6000),
        )
        val diarization = listOf(
            DiarizationSegment(0.0, 2.0, "SPEAKER_01"),
            DiarizationSegment(2.0, 4.0, "SPEAKER_00"),
            DiarizationSegment(4.0, 6.0, "SPEAKER_01"),
        )

        val result = mock.applyDiarization(segments, diarization)

        assertEquals("Caller 1", result[0].speakerLabel, "SPEAKER_01 appears first → index 1")
        assertEquals("Caller 2", result[1].speakerLabel, "SPEAKER_00 appears second → index 2")
        assertEquals("Caller 1", result[2].speakerLabel, "SPEAKER_01 again → same index 1")
    }

    @Test
    fun `OnnxDiarizationBackend isAvailable returns false`() = runBlocking {
        assertFalse(OnnxDiarizationBackend().isAvailable())
    }

    @Test
    fun `OnnxDiarizationBackend areModelsAvailable returns false`() = runBlocking {
        assertFalse(OnnxDiarizationBackend().areModelsAvailable())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun seg(speaker: String, startMs: Long, endMs: Long) = TranscriptSegment(
        id = "$speaker-$startMs",
        meetingId = "test",
        speakerLabel = speaker,
        text = "test",
        startMs = startMs,
        endMs = endMs,
    )

    private class MockDiarizationBackend : DiarizationBackend {
        override suspend fun isAvailable(): Boolean = true
        override suspend fun areModelsAvailable(): Boolean = true
        override suspend fun downloadModels(): Unit = Unit
        override suspend fun diarize(
            audioFilePath: String,
            hfToken: String,
            maxSpeakers: Int?,
            timeoutMinutes: Long,
        ): List<DiarizationSegment> = emptyList()
    }
}
