package com.meetingnotes.domain.model

import kotlinx.coroutines.flow.StateFlow

/**
 * State machine for the recording lifecycle.
 *
 * Transitions:
 *   Idle → Recording → Stopping → Transcribing → Diarizing → CorrectingTranscript → Summarizing → Exporting → Complete
 *                                                                                                                  ↓
 *                                                                                                            Error(stage, message)
 *
 * Diarizing and CorrectingTranscript are non-fatal stages — errors are swallowed and the
 * pipeline continues to Summarizing with the original speaker labels / text.
 *
 * Each state is emitted via a [StateFlow<RecordingState>] in the ViewModel.
 */
sealed class RecordingState {

    /** No active session. */
    data object Idle : RecordingState()

    /**
     * Audio is being captured.
     * @param startedAt Epoch millis when recording started
     * @param durationMs Live-updating elapsed duration (from a ticker flow)
     */
    data class Recording(
        val startedAt: Long,
        val durationMs: Long = 0L,
    ) : RecordingState()

    /** User pressed Stop; finishing buffer flush and WAV finalisation. */
    data object Stopping : RecordingState()

    /**
     * Whisper transcription running.
     * @param progress 0–100 percent complete
     * @param stage Human-readable label for the current sub-step
     * @param currentChunk The chunk being processed right now (1-based), or 0 if unknown
     * @param totalChunks Total number of chunks, or 0 if unknown
     */
    data class Transcribing(
        val progress: Int = 0,
        val stage: String = "",
        val currentChunk: Int = 0,
        val totalChunks: Int = 0,
    ) : RecordingState()

    /**
     * Post-hoc speaker diarization running via Python/pyannote sidecar.
     * Non-fatal — pipeline continues on failure with original "Caller" labels.
     */
    data object Diarizing : RecordingState()

    /**
     * LLM transcript error correction running via Ollama.
     * Non-fatal — pipeline continues on failure with original transcribed text.
     */
    data object CorrectingTranscript : RecordingState()

    /** LLM summarisation in progress. */
    data object Summarizing : RecordingState()

    /** Writing Logseq page and journal entry. */
    data object Exporting : RecordingState()

    /**
     * Pipeline complete.
     * @param meeting The completed [Meeting] record
     * @param summarySkipped True when the LLM was unavailable and summarisation was soft-skipped
     */
    data class Complete(
        val meeting: Meeting,
        val summarySkipped: Boolean = false,
    ) : RecordingState()

    /**
     * A recoverable error occurred.
     * @param stage Human-readable name of the failed stage (e.g. "Transcription")
     * @param message User-facing error description
     * @param retryable Whether the [PipelineOrchestrator] can resume from this stage
     */
    data class Error(
        val stage: String,
        val message: String,
        val retryable: Boolean = false,
    ) : RecordingState()
}
