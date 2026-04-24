package com.meetingnotes.domain.model

import kotlinx.serialization.Serializable

/**
 * Per-stage timing breakdown of the transcription pipeline.
 * Persisted alongside the meeting as JSON for later display.
 */
@Serializable
data class TranscriptionMetrics(
    val preprocessMs: Long,    // splitChannels + convertToWhisperFormat × 2
    val modelLoadMs: Long,     // WhisperService.loadModel()
    val micInferenceMs: Long,  // whisper.full() on mic channel
    val sysInferenceMs: Long,  // whisper.full() on sys channel
    val persistMs: Long,       // insertSegment() loop
)
