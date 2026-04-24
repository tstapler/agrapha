package com.meetingnotes.audio

import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.domain.model.Meeting
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Orchestrates simultaneous mic (channel 0) + system audio (channel 1) capture.
 *
 * Each channel is streamed independently to a temp mono WAV file to keep memory
 * usage bounded (BUG-008). On [stopRecording] the two mono files are merged into
 * a single stereo WAV, then the mono temps are deleted.
 *
 * Channel assignment (consistent with [AudioPreprocessor.splitChannels]):
 *   - Channel 0: microphone  → labeled "You" by the diarizer
 *   - Channel 1: system audio → labeled "Caller" by the diarizer
 *
 * Live chunks: every [CHUNK_DURATION_MS], a [LiveChunk] is emitted via [liveChunks]
 * so callers can begin transcription while recording is still in progress.
 */
class RecordingSessionManager(
    private val repository: MeetingRepository,
    private val storage: FileStorageService,
) {
    /** A snapshot of one channel pair ready for live transcription. */
    data class LiveChunk(
        val meetingId: String,
        val micPcm: ByteArray,
        val sysPcm: ByteArray,
        /** Start of this chunk within the full recording, in milliseconds. */
        val offsetMs: Long,
    )

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var _liveChunks = Channel<LiveChunk>(Channel.UNLIMITED)
    /** Emits every [CHUNK_DURATION_MS] while recording is active. Completes on stop. */
    val liveChunks: Flow<LiveChunk> get() = _liveChunks.receiveAsFlow()

    /** End offset (ms) of the last live chunk emitted; 0 if no chunks yet. */
    @Volatile
    var lastProcessedMs: Long = 0L
        private set

    private var recordingJob: Job? = null
    private var currentMeetingId: String? = null
    private var recordingStartMs: Long = 0L
    private val micService = MicCaptureService()

    companion object {
        /** Must match AudioPreprocessor.DEFAULT_CHUNK_MS. */
        const val CHUNK_DURATION_MS = 3 * 60 * 1000L
    }

    /**
     * Begin capturing mic + system audio.
     * @return The meeting ID assigned to this session.
     */
    fun startRecording(scope: CoroutineScope): String {
        val meetingId = UUID.randomUUID().toString()
        currentMeetingId = meetingId
        recordingStartMs = System.currentTimeMillis()
        _durationMs.value = 0L
        _liveChunks = Channel(Channel.UNLIMITED)
        lastProcessedMs = 0L

        val micPath = storage.getAudioFilePath("${meetingId}_mic")
        val sysPath = storage.getAudioFilePath("${meetingId}_sys")
        File(micPath).parentFile?.mkdirs()

        val micWriter = WavWriter(File(micPath), sampleRate = 16_000, channels = 1)
        val sysWriter = WavWriter(File(sysPath), sampleRate = 16_000, channels = 1)

        recordingJob = scope.launch {
            val micJob = launch(Dispatchers.IO) {
                try {
                    micService.captureFlow().collect { samples ->
                        synchronized(micWriter) { micWriter.writeSamples(samples) }
                    }
                } catch (_: Exception) { /* mic unavailable — proceed with silence */ }
            }

            val sysJob = launch(Dispatchers.IO) {
                val buf = FloatArray(1600)
                // Start the ScreenCaptureKit audio stream. Failures here are non-fatal —
                // the system channel will be silent rather than crashing the recording.
                var captureStarted = false
                try {
                    captureStarted = ScreenCaptureJniBridge.nativeStartCapture(16_000)
                } catch (_: Throwable) { /* JNI not loaded (tests) or permission denied */ }

                try {
                    while (isActive) {
                        try {
                            val n = ScreenCaptureJniBridge.nativeReadBuffer(buf)
                            if (n > 0) synchronized(sysWriter) { sysWriter.writeSamples(buf.copyOf(n)) }
                        } catch (_: Exception) { /* native read failed — skip chunk */ }
                        delay(10L)  // outside inner try so CancellationException propagates
                    }
                } finally {
                    if (captureStarted) runCatching { ScreenCaptureJniBridge.nativeStopCapture() }
                }
            }

            val tickJob = launch {
                var nextChunkMs = CHUNK_DURATION_MS
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - recordingStartMs
                    _durationMs.value = elapsed
                    // Emit a live chunk once enough audio has accumulated
                    if (elapsed >= nextChunkMs) {
                        val fromSample = lastProcessedMs * 16_000L / 1000L
                        val id = currentMeetingId
                        if (id != null) {
                            val micPcm = synchronized(micWriter) { micWriter.snapshotPcmFrom(fromSample) }
                            val sysPcm = synchronized(sysWriter) { sysWriter.snapshotPcmFrom(fromSample) }
                            _liveChunks.trySend(LiveChunk(id, micPcm, sysPcm, lastProcessedMs))
                            lastProcessedMs = nextChunkMs
                        }
                        nextChunkMs += CHUNK_DURATION_MS
                    }
                    delay(500L)
                }
            }

            try {
                awaitCancellation()
            } finally {
                micJob.cancelAndJoin()
                sysJob.cancelAndJoin()
                tickJob.cancel()
                micService.stop()
                micWriter.close()
                sysWriter.close()
            }
        }

        return meetingId
    }

    /**
     * Stop recording, merge channels into a stereo WAV, persist the Meeting, and return it
     * along with the end offset of the last live chunk already transcribed ([lastProcessedMs]).
     * Must be called from a coroutine — this suspends until everything is finalised.
     */
    suspend fun stopRecording(): Pair<Meeting?, Long> = withContext(Dispatchers.IO) {
        val processedMs = lastProcessedMs
        _liveChunks.close()

        val finalDurationMs = _durationMs.value
        recordingJob?.cancelAndJoin()
        recordingJob = null

        val meetingId = currentMeetingId ?: return@withContext null to processedMs
        currentMeetingId = null
        _durationMs.value = 0L

        val now = System.currentTimeMillis()
        val startedAt = recordingStartMs
        val durationSec = finalDurationMs / 1000L

        val micPath = storage.getAudioFilePath("${meetingId}_mic")
        val sysPath = storage.getAudioFilePath("${meetingId}_sys")
        val stereoPath = storage.getAudioFilePath(meetingId)

        mergeToStereo(micPath, sysPath, stereoPath)

        File(micPath).delete()
        File(sysPath).delete()

        val meeting = Meeting(
            id = meetingId,
            title = buildTitle(startedAt),
            startedAt = startedAt,
            endedAt = now,
            durationSeconds = durationSec,
            audioFilePath = stereoPath,
        )
        repository.insertMeeting(meeting)
        repository.updateMeetingEnd(meetingId, now, durationSec)
        meeting to processedMs
    }

    // ── Private ──────────────────────────────────────────────────────────────

    /**
     * Merge two mono WAVs into a stereo WAV by interleaving samples.
     *
     * Streams both files in 64 KB chunks so neither the mic nor sys recording
     * is fully loaded into memory — saves ~3× per-channel size compared to
     * loading both FloatArrays + the interleaved array simultaneously.
     */
    private fun mergeToStereo(micPath: String, sysPath: String, stereoPath: String) {
        val micFile = File(micPath)
        val sysFile = File(sysPath)

        // Log peak amplitude from first second (32 KB) only — no full-file read needed
        val micPeak = wavPeakFirstSecond(micFile)
        val sysPeak = wavPeakFirstSecond(sysFile)
        val micFrames = wavFrameCount(micFile)
        val sysFrames = wavFrameCount(sysFile)
        System.err.println("[RecordingSessionManager] channel peaks (first 1s): " +
            "mic=${"%.4f".format(micPeak)} sys=${"%.4f".format(sysPeak)} " +
            "micFrames=$micFrames sysFrames=$sysFrames")

        val headerSize = 44
        val bufSamples = 32_768  // 32K frames per read (~64 KB)
        val micBuf  = ByteArray(bufSamples * 2)
        val sysBuf  = ByteArray(bufSamples * 2)
        val stereoBuf = FloatArray(bufSamples * 2)

        val stereoWriter = WavWriter(File(stereoPath), sampleRate = 16_000, channels = 2)

        fun openAndSkipHeader(file: File): java.io.InputStream? {
            if (!file.exists()) return null
            return file.inputStream().buffered().also { s ->
                var skipped = 0L
                while (skipped < headerSize) skipped += s.skip(headerSize - skipped)
            }
        }

        fun readFull(stream: java.io.InputStream?, buf: ByteArray): Int {
            if (stream == null) return 0
            var total = 0
            while (total < buf.size) {
                val r = stream.read(buf, total, buf.size - total)
                if (r == -1) break
                total += r
            }
            return total
        }

        fun decodeFloat(buf: ByteArray, byteIdx: Int): Float {
            val lo = buf[byteIdx].toInt() and 0xFF
            val hi = buf[byteIdx + 1].toInt()
            return ((hi shl 8) or lo) / 32768f
        }

        val micStream = openAndSkipHeader(micFile)
        val sysStream = openAndSkipHeader(sysFile)

        try {
            while (true) {
                val micRead = readFull(micStream, micBuf)
                val sysRead = readFull(sysStream, sysBuf)
                val frames = maxOf(micRead, sysRead) / 2
                if (frames == 0) break

                for (i in 0 until frames) {
                    val byteIdx = i * 2
                    stereoBuf[i * 2]     = if (byteIdx + 1 < micRead) decodeFloat(micBuf, byteIdx) else 0f
                    stereoBuf[i * 2 + 1] = if (byteIdx + 1 < sysRead) decodeFloat(sysBuf, byteIdx) else 0f
                }
                stereoWriter.writeSamples(stereoBuf.copyOf(frames * 2))
            }
        } finally {
            micStream?.close()
            sysStream?.close()
        }

        stereoWriter.close()
    }

    /** Peak absolute amplitude from the first second (16000 samples) of a 16kHz 16-bit WAV. */
    private fun wavPeakFirstSecond(file: File): Float {
        if (!file.exists()) return 0f
        val headerSize = 44
        val buf = ByteArray(16_000 * 2)  // 1 second at 16kHz
        file.inputStream().use { stream ->
            var skipped = 0L
            while (skipped < headerSize) skipped += stream.skip(headerSize - skipped)
            stream.read(buf)
        }
        var peak = 0f
        for (i in 0 until buf.size / 2) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt()
            val s = kotlin.math.abs(((hi shl 8) or lo) / 32768f)
            if (s > peak) peak = s
        }
        return peak
    }

    /** Number of audio frames (samples) in a 16-bit mono WAV from its file size. */
    private fun wavFrameCount(file: File): Long =
        if (file.exists()) (file.length() - 44).coerceAtLeast(0) / 2 else 0L

    private fun buildTitle(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return "Meeting ${fmt.format(Date(epochMs))}"
    }
}
