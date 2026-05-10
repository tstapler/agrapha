package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment

/**
 * Common interface for all transcription backends.
 *
 * Implementations:
 * - [WhisperTranscriptionBackend] — cross-platform GGML via whisper-jni (requires model download)
 * - [AppleSpeechBackend] — macOS on-device SFSpeechRecognizer (no download, uses Neural Engine)
 */
interface TranscriptionBackend {
    val id: String
    val displayName: String

    /** True if this backend can run on the current OS/platform. */
    val isAvailable: Boolean

    /** True if [transcribe] can be called immediately without first calling [prepare]. */
    val isReady: Boolean

    /**
     * Pre-load any model or resource needed.
     * No-op for backends with built-in models (e.g. [AppleSpeechBackend]).
     *
     * @param modelPath Absolute path to the GGML model file (used by [WhisperTranscriptionBackend])
     */
    fun prepare(modelPath: String = "") {}

    /**
     * Transcribe a 16kHz mono WAV file into timestamped segments.
     *
     * @param audioPath       Absolute path to the WAV file
     * @param meetingId       ID attached to every returned segment
     * @param speakerLabel    Optional fixed speaker label for all segments
     * @param chunkOffsetMs   Milliseconds added to all segment timestamps
     * @param progressCallback Receives 0–100 progress estimates; may not fire on all backends
     */
    fun transcribe(
        audioPath: String,
        meetingId: String,
        speakerLabel: String? = null,
        chunkOffsetMs: Long = 0L,
        progressCallback: ((Int) -> Unit)? = null,
    ): List<TranscriptSegment>

    /** Release held native resources. Safe to call multiple times. */
    fun close() {}
}
