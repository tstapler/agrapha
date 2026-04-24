package com.meetingnotes.domain

import com.meetingnotes.audio.RecordingSessionManager
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.llm.LlmProviderFactory
import com.meetingnotes.domain.model.RecordingState
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.export.LogseqExporter
import com.meetingnotes.transcription.AudioPreprocessor
import com.meetingnotes.domain.audio.DiarizationFailedException
import com.meetingnotes.domain.audio.DiarizationTimeoutException
import com.meetingnotes.domain.audio.DiarizationUnavailableException
import com.meetingnotes.transcription.PyannoteDiarizationBackend
import com.meetingnotes.transcription.WhisperService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * App-level background worker that processes the post-recording pipeline
 * (transcription → summarization → export) for queued meetings without
 * blocking the recording UI.
 *
 * Meetings are processed one at a time in FIFO order. [processingIds] exposes
 * the set of meeting IDs that are currently queued or being processed so the
 * History screen can display a "Processing" badge.
 *
 * Lifecycle: create once at app startup (in AppRoot), call [close] on app exit.
 */
class PipelineQueueExecutor(
    private val repository: MeetingRepository,
    private val settingsRepository: SettingsRepository,
    whisperService: WhisperService = WhisperService(),
) : Closeable {

    private data class QueueEntry(val meetingId: String, val startFromMs: Long = 0L)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val queue = Channel<QueueEntry>(Channel.UNLIMITED)
    private val whisperService = whisperService

    private val _processingIds = MutableStateFlow<Set<String>>(emptySet())
    /** Meeting IDs that are queued or actively being processed. */
    val processingIds: StateFlow<Set<String>> = _processingIds.asStateFlow()

    private val _jobProgress = MutableStateFlow<Map<String, RecordingState.Transcribing>>(emptyMap())
    /** Per-meeting transcription progress for currently-processing jobs. */
    val jobProgress: StateFlow<Map<String, RecordingState.Transcribing>> = _jobProgress.asStateFlow()

    init {
        // Eagerly load the Whisper model so it is warm before the first recording finishes.
        scope.launch {
            val settings = withContext(Dispatchers.IO) { settingsRepository.load() }
            val modelPath = settings.whisperModelPath
            if (modelPath.isNotBlank() && File(modelPath).exists()) {
                log("init: pre-loading model ${File(modelPath).name}")
                runCatching { withContext(Dispatchers.IO) { whisperService.loadModel(modelPath) } }
                    .onFailure { log("init: model pre-load failed: ${it.message}") }
                    .onSuccess { log("init: model pre-load complete") }
            } else {
                log("init: no model configured for pre-load (path='$modelPath')")
            }
        }

        scope.launch {
            for (entry in queue) {
                processOne(entry)
                _processingIds.value -= entry.meetingId
            }
        }
    }

    /**
     * Add [meetingId] to the processing queue. Returns immediately — processing
     * happens asynchronously on a background coroutine.
     *
     * [startFromMs] skips chunks that were already transcribed via [transcribeLiveChunk]
     * during the recording so only the tail is processed at stop time.
     */
    fun enqueue(meetingId: String, startFromMs: Long = 0L) {
        log("enqueue: meetingId=$meetingId startFromMs=$startFromMs")
        _processingIds.value += meetingId
        queue.trySend(QueueEntry(meetingId, startFromMs))
    }

    /**
     * Transcribe one live chunk in the background without running the full pipeline.
     * Called for each [RecordingSessionManager.LiveChunk] emitted during recording.
     */
    fun transcribeLiveChunk(chunk: RecordingSessionManager.LiveChunk) {
        log("transcribeLiveChunk: meetingId=${chunk.meetingId} offset=${chunk.offsetMs}ms")
        scope.launch(TranscriptionUseCase.transcriptionDispatcher) {
            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val tag = "${chunk.meetingId}_live_${chunk.offsetMs}"
            val micFile = File(tempDir, "${tag}_mic.wav")
            val sysFile = File(tempDir, "${tag}_sys.wav")
            val micWhisper = File(tempDir, "${tag}_mic_16k.wav")
            val sysWhisper = File(tempDir, "${tag}_sys_16k.wav")
            val tLive = System.currentTimeMillis()
            try {
                writePcmWav(chunk.micPcm, micFile)
                writePcmWav(chunk.sysPcm, sysFile)
                AudioPreprocessor.convertToWhisperFormat(micFile.toPath(), micWhisper.toPath())
                AudioPreprocessor.convertToWhisperFormat(sysFile.toPath(), sysWhisper.toPath())

                val n = Runtime.getRuntime().availableProcessors()
                val threadsEach = (n / 2).coerceAtLeast(1)
                val micSegs = whisperService.transcribe(
                    micWhisper.absolutePath, chunk.meetingId, "You", threadsEach, chunk.offsetMs,
                )
                val sysSegs = whisperService.transcribe(
                    sysWhisper.absolutePath, chunk.meetingId, "Caller", threadsEach, chunk.offsetMs,
                )
                withContext(Dispatchers.IO) {
                    (micSegs + sysSegs).sortedBy { it.startMs }
                        .forEach { repository.insertSegment(it) }
                }
                log("transcribeLiveChunk: complete offset=${chunk.offsetMs}ms segs=${micSegs.size + sysSegs.size} took ${System.currentTimeMillis() - tLive}ms")
            } catch (e: Exception) {
                // Live chunk failure is non-fatal — the stop-time pipeline will cover missed audio.
                log("transcribeLiveChunk: FAILED offset=${chunk.offsetMs}ms after ${System.currentTimeMillis() - tLive}ms: ${e.message}")
            } finally {
                listOf(micFile, sysFile, micWhisper, sysWhisper).forEach { it.delete() }
            }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun processOne(entry: QueueEntry) {
        log("processOne: start meetingId=${entry.meetingId} startFromMs=${entry.startFromMs}")
        val meeting = kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.getMeetingById(entry.meetingId)
        } ?: run {
            log("processOne: meeting not found in DB: ${entry.meetingId}")
            return
        }

        try {
            val settings = kotlinx.coroutines.withContext(Dispatchers.IO) {
                settingsRepository.load()
            }
            log("processOne: model=${java.io.File(settings.whisperModelPath).name} provider=${settings.llmProvider} meetingId=${entry.meetingId}")
            val provider = LlmProviderFactory.create(settings)
            val useCase = TranscriptionUseCase(repository, whisperService, entry.startFromMs)
            val exporter = LogseqExporter()
            val localState = MutableStateFlow<RecordingState>(RecordingState.Idle)

            // Observe localState to expose chunk-level progress to the UI
            val observeJob = scope.launch {
                localState.collect { state ->
                    if (state is RecordingState.Transcribing) {
                        _jobProgress.value = _jobProgress.value + (entry.meetingId to state)
                    }
                }
            }

            // ── Optional diarization runner ────────────────────────────────
            val diarizationRunnerFull: (suspend (String, String) -> List<TranscriptSegment>?)? =
                if (settings.diarizationEnabled && settings.huggingFaceToken.isNotBlank()) {
                    { audioPath, meetingId ->
                        try {
                            val (_, sysPath) = AudioPreprocessor.splitChannels(java.nio.file.Path.of(audioPath))
                            val backend = PyannoteDiarizationBackend()
                            val maxSpeakers = settings.diarizationMaxSpeakers.takeIf { it > 0 }
                            val diarizationSegments = backend.diarize(
                                audioFilePath = sysPath.toString(),
                                hfToken = settings.huggingFaceToken,
                                maxSpeakers = maxSpeakers,
                            )
                            val segments = withContext(Dispatchers.IO) {
                                repository.getSegmentsByMeetingId(meetingId)
                            }
                            backend.applyDiarization(segments, diarizationSegments)
                        } catch (e: DiarizationUnavailableException) {
                            log("diarization unavailable: ${e.message}")
                            null
                        } catch (e: DiarizationTimeoutException) {
                            log("diarization timed out: ${e.message}")
                            null
                        } catch (e: DiarizationFailedException) {
                            log("diarization failed (exit=${e.exitCode}): ${e.message}")
                            null
                        } catch (e: Exception) {
                            log("diarization runner error: ${e.message}")
                            null
                        }
                    }
                } else null

            // ── Optional correction runner ─────────────────────────────────
            val correctionRunnerFull: (suspend (List<TranscriptSegment>) -> List<TranscriptSegment>)? =
                if (settings.correctionEnabled) {
                    { segments ->
                        try {
                            provider.correct(segments, settings)
                        } catch (e: Exception) {
                            log("correction runner error: ${e.message}")
                            segments  // fail-safe: return original
                        }
                    }
                } else null

            val pipe = PipelineOrchestrator(
                transcriptionUseCase = useCase,
                llmProvider = provider,
                exporter = exporter,
                repository = repository,
                settingsRepository = settingsRepository,
                stateFlow = localState,
                diarizationRunner = diarizationRunnerFull,
                correctionRunner = correctionRunnerFull,
            )
            val tPipeline = System.currentTimeMillis()
            pipe.run(meeting, settings.whisperModelPath)
            observeJob.cancel()
            log("processOne: complete meetingId=${entry.meetingId} took ${System.currentTimeMillis() - tPipeline}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pipeline errors are non-fatal for the queue — the next meeting will still process.
            // The user can see the failed meeting has no transcript in History.
            log("processOne: FAILED meetingId=${entry.meetingId}: ${e.message}")
        } finally {
            _jobProgress.value = _jobProgress.value - entry.meetingId
        }
    }

    private fun log(msg: String) =
        System.err.println("[${java.time.Instant.now()}] [PipelineQueueExecutor] $msg")

    override fun close() {
        queue.close()
        scope.cancel()
        whisperService.close()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Write raw 16-bit little-endian mono PCM bytes as a valid WAV file at 16kHz. */
    private fun writePcmWav(pcm: ByteArray, file: File) {
        val dataBytes = pcm.size.toLong()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)          // sub-chunk size
        header.putShort(1)         // PCM
        header.putShort(1)         // mono
        header.putInt(16_000)      // sample rate
        header.putInt(16_000 * 2)  // byte rate
        header.putShort(2)         // block align
        header.putShort(16)        // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(header.array())
            raf.write(pcm)
        }
    }
}
