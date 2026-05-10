package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment

/** [TranscriptionBackend] backed by [WhisperService] (GGML, cross-platform). */
class WhisperTranscriptionBackend(
    private val service: WhisperService = WhisperService(),
) : TranscriptionBackend {

    override val id = "whisper"
    override val displayName = "Whisper (GGML, on-device)"
    override val isAvailable = true
    override val isReady: Boolean get() = service.isLoaded

    /** Direct access to the underlying [WhisperService] for callers that manage model lifecycle. */
    val whisperService: WhisperService get() = service

    override fun prepare(modelPath: String) {
        if (modelPath.isNotBlank() && (!service.isLoaded || service.loadedModelPath != modelPath)) {
            service.loadModel(modelPath)
        }
    }

    override fun transcribe(
        audioPath: String,
        meetingId: String,
        speakerLabel: String?,
        chunkOffsetMs: Long,
        progressCallback: ((Int) -> Unit)?,
    ): List<TranscriptSegment> = service.transcribe(
        audioPath = audioPath,
        meetingId = meetingId,
        speakerLabel = speakerLabel,
        chunkOffsetMs = chunkOffsetMs,
        progressCallback = progressCallback,
    )

    override fun close() = service.close()
}
