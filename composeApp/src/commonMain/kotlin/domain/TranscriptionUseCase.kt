package com.meetingnotes.domain

import com.meetingnotes.domain.model.TranscriptionMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Progress snapshot emitted during transcription. */
data class TranscriptionProgress(
    val stage: String,
    val percent: Int,  // 0–100
    /** 1-based index of the chunk currently being processed; 0 when unknown. */
    val currentChunk: Int = 0,
    /** Total number of chunks; 0 when unknown. */
    val totalChunks: Int = 0,
    /** Per-stage timing breakdown, populated only on the final (100 %) event. */
    val metrics: TranscriptionMetrics? = null,
)

/**
 * Platform-specific transcription use case.
 *
 * Orchestrates: preprocess → transcribe → diarize → save.
 * Emits [TranscriptionProgress] updates throughout.
 */
expect class TranscriptionUseCase() {

    /**
     * Transcribe the audio at [audioFilePath] for [meetingId].
     * Returns the transcribed [TranscriptSegment] list via a [Flow].
     *
     * The flow completes when transcription is saved to the repository.
     * Cancelling the job stops transcription cleanly — partial state is NOT persisted.
     */
    fun transcribe(
        audioFilePath: String,
        meetingId: String,
        modelPath: String,
    ): Flow<TranscriptionProgress>
}
