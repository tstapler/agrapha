package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
private data class RawSegment(val text: String, val start_ms: Long, val end_ms: Long)

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * macOS-native transcription using [SFSpeechRecognizer] (Apple Speech framework).
 *
 * Advantages over Whisper:
 * - No model download — uses the same on-device model as macOS dictation
 * - Leverages the Apple Neural Engine on Apple Silicon (near-instant on short clips)
 * - Respects macOS language settings automatically
 *
 * Limitations:
 * - macOS only; [isAvailable] returns false on Linux/Windows
 * - Requires a one-time speech recognition permission dialog
 * - May be less accurate than large Whisper models on technical vocabulary
 * - Maximum audio duration is ~60 seconds per request (Apple SDK limit)
 */
class AppleSpeechBackend : TranscriptionBackend {

    override val id = "apple-speech"
    override val displayName = "Apple Speech (on-device, no download)"
    override val isAvailable: Boolean get() = AppleSpeechJniBridge.isAvailable()
    override val isReady = true  // no model loading step

    override fun transcribe(
        audioPath: String,
        meetingId: String,
        speakerLabel: String?,
        chunkOffsetMs: Long,
        progressCallback: ((Int) -> Unit)?,
    ): List<TranscriptSegment> {
        progressCallback?.invoke(5)
        val rawJson = AppleSpeechJniBridge.transcribe(audioPath)
        progressCallback?.invoke(90)
        return parseSegments(rawJson, meetingId, speakerLabel, chunkOffsetMs)
            .also { progressCallback?.invoke(100) }
    }

    private fun parseSegments(
        rawJson: String,
        meetingId: String,
        speakerLabel: String?,
        chunkOffsetMs: Long,
    ): List<TranscriptSegment> = lenientJson
        .decodeFromString<List<RawSegment>>(rawJson)
        .filter { it.text.trim().length >= 3 && (it.end_ms - it.start_ms) >= 200L }
        .map { seg ->
            TranscriptSegment(
                id = UUID.randomUUID().toString(),
                meetingId = meetingId,
                speakerLabel = speakerLabel,
                startMs = seg.start_ms + chunkOffsetMs,
                endMs = seg.end_ms + chunkOffsetMs,
                text = seg.text.trim(),
            )
        }
}
