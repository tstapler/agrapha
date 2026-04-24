package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment
import java.nio.file.Path

/**
 * Labels transcript segments as "You" (microphone) or "Caller" (system audio)
 * using the dual-channel heuristic: transcribe each channel separately, then merge.
 *
 * This avoids on-device ML diarization (pyannote) while delivering acceptable
 * speaker attribution for two-party calls. For three or more parties the system
 * channel will blend all remote participants under the "Caller" label.
 *
 * @see WhisperService for the underlying transcription engine
 */
class DualChannelDiarizer(private val whisperService: WhisperService) {

    /**
     * Transcribe two mono WAV files and return a merged, sorted segment list.
     *
     * @param micPath          Path to the microphone channel WAV (labeled "You")
     * @param sysPath          Path to the system audio channel WAV (labeled "Caller")
     * @param meetingId        Meeting ID propagated to all segments
     * @param progressCallback Optional callback receiving 0–100 progress within the diarization phase.
     *                         Emits at: ~30% (starting mic), ~57% (starting sys), ~90% (merging).
     */
    fun diarize(
        micPath: Path,
        sysPath: Path,
        meetingId: String,
        progressCallback: ((Int) -> Unit)? = null,
    ): List<TranscriptSegment> {
        // Mic pass: maps WhisperService's internal 5–95 range → 30–55
        progressCallback?.invoke(30)
        val micSegments = whisperService.transcribe(
            audioPath = micPath.toString(),
            meetingId = meetingId,
            speakerLabel = "You",
            progressCallback = { p -> progressCallback?.invoke(remapProgress(p, dstLow = 30, dstHigh = 55)) },
        )

        // Sys pass: maps WhisperService's internal 5–95 range → 57–82
        progressCallback?.invoke(57)
        val sysSegments = whisperService.transcribe(
            audioPath = sysPath.toString(),
            meetingId = meetingId,
            speakerLabel = "Caller",
            progressCallback = { p -> progressCallback?.invoke(remapProgress(p, dstLow = 57, dstHigh = 82)) },
        )

        progressCallback?.invoke(90)

        // Merge both channels chronologically.
        // Overlapping timestamps are kept — they represent crosstalk.
        return (micSegments + sysSegments).sortedBy { it.startMs }
    }

    /**
     * Linearly maps a progress value from WhisperService's internal 5–95 range into a
     * caller-defined [dstLow]..[dstHigh] subrange.
     */
    private fun remapProgress(p: Int, dstLow: Int, dstHigh: Int): Int =
        dstLow + (p - WHISPER_PROGRESS_LOW) * (dstHigh - dstLow) / WHISPER_PROGRESS_RANGE

    companion object {
        private const val WHISPER_PROGRESS_LOW = 5
        private const val WHISPER_PROGRESS_RANGE = 90  // 95 - 5
    }
}
