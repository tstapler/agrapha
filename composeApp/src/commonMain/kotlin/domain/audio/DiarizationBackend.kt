package com.meetingnotes.domain.audio

import com.meetingnotes.domain.model.TranscriptSegment

// ── Exceptions ────────────────────────────────────────────────────────────────

/** Thrown when the backend is not operational (Python not found, sidecar script missing, or diarization disabled). */
class DiarizationUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when diarization processing exceeds the configured timeout. */
class DiarizationTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the diarization process fails — e.g. non-zero exit code,
 * unreadable output, or JSON parse error.
 *
 * @param exitCode The subprocess exit code, or null when the failure is not process-related.
 */
class DiarizationFailedException(
    message: String,
    val exitCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

// ── Value types ───────────────────────────────────────────────────────────────

/**
 * A single speaker-labelled time range produced by a diarization backend.
 *
 * @param startSec Start of the segment in seconds from the beginning of the audio file.
 * @param endSec   End of the segment in seconds.
 * @param speaker  Backend-assigned speaker identifier (e.g. "SPEAKER_00").
 */
data class DiarizationSegment(
    val startSec: Double,
    val endSec: Double,
    val speaker: String,
)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Strategy interface for speaker diarization backends.
 *
 * Implementations: [com.meetingnotes.transcription.PyannoteDiarizationBackend]
 * (Python subprocess via pyannote.audio).
 *
 * **Usage contract:**
 * - Diarization is applied to the **system audio channel only** (the caller channel).
 *   Mic segments ("You") are never passed to [diarize] and must not be affected by
 *   [applyDiarization].
 * - Diarization is **non-fatal by design**: callers are expected to catch all three
 *   exception types and fall back gracefully to the original "Caller" label.
 * - All implementations must be safe to call from a coroutine context on
 *   [kotlinx.coroutines.Dispatchers.IO].
 */
interface DiarizationBackend {

    /**
     * Check whether this backend is operational.
     *
     * For Python subprocess backends, this verifies that a `python3` or `python`
     * interpreter is on the PATH and that the sidecar script can be located.
     * This call should be fast (no model loading) and safe to call repeatedly.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Run speaker diarization on the mono system-audio WAV at [audioFilePath].
     *
     * @param audioFilePath  Absolute path to the system-audio WAV file (mono, caller channel).
     * @param hfToken        HuggingFace access token required for pyannote model download.
     * @param maxSpeakers    Optional ceiling on the number of distinct speakers to identify.
     * @param timeoutMinutes Hard timeout; the backend must abort and throw if exceeded.
     * @return               Ordered list of [DiarizationSegment]s covering the audio file.
     *
     * @throws DiarizationUnavailableException if Python or the sidecar script cannot be found
     * @throws DiarizationTimeoutException if processing exceeds [timeoutMinutes]
     * @throws DiarizationFailedException for process errors, non-zero exit codes, or parse failures
     */
    suspend fun diarize(
        audioFilePath: String,
        hfToken: String,
        maxSpeakers: Int? = null,
        timeoutMinutes: Long = 60,
    ): List<DiarizationSegment>

    /**
     * Merge [diarizationSegments] into [segments], replacing every "Caller" label
     * with a numbered variant ("Caller 1", "Caller 2", …).
     *
     * The speaker number for each segment is determined by whichever [DiarizationSegment]
     * has the greatest timestamp overlap with the transcript segment. Speaker indices are
     * assigned 1-based in order of first appearance on the diarization timeline
     * (i.e. SPEAKER_00 → 1, SPEAKER_01 → 2, …) — stable regardless of input order.
     *
     * Behaviour guarantees:
     * - Mic segments (speakerLabel == "You") are returned **unchanged**.
     * - Segments with no diarization overlap retain their original label.
     * - The input lists are never mutated; a new list is always returned.
     *
     * @param segments              Original transcript segments from the ASR pipeline.
     * @param diarizationSegments   Speaker-labelled time ranges returned by [diarize].
     * @return                      New list with updated "Caller N" speaker labels.
     */
    fun applyDiarization(
        segments: List<TranscriptSegment>,
        diarizationSegments: List<DiarizationSegment>,
    ): List<TranscriptSegment>
}
