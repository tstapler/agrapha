package com.meetingnotes.domain

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.domain.model.TranscriptionMetrics
import com.meetingnotes.transcription.AudioPreprocessor
import com.meetingnotes.transcription.WhisperService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.Executors

actual class TranscriptionUseCase actual constructor() {

    private var repository: MeetingRepository? = null
    private var sharedWhisperService: WhisperService? = null
    /** First chunk index to process; chunks before this were already transcribed live. */
    private var startFromChunkIdx: Int = 0

    constructor(repository: MeetingRepository) : this() {
        this.repository = repository
    }

    /**
     * Accepts a [WhisperService] that was pre-loaded at app startup.
     * When provided the model-load step is skipped during transcription,
     * eliminating the wait and the parallel-load race condition.
     */
    constructor(repository: MeetingRepository, whisperService: WhisperService) : this() {
        this.repository = repository
        this.sharedWhisperService = whisperService
    }

    /**
     * Like the two-arg constructor but also skips the first [startFromMs] of audio
     * that was already transcribed via live chunk processing during recording.
     */
    constructor(repository: MeetingRepository, whisperService: WhisperService, startFromMs: Long) : this() {
        this.repository = repository
        this.sharedWhisperService = whisperService
        this.startFromChunkIdx = (startFromMs / LIVE_CHUNK_DURATION_MS).toInt()
    }

    actual fun transcribe(
        audioFilePath: String,
        meetingId: String,
        modelPath: String,
    ): Flow<TranscriptionProgress> = channelFlow {
        val tStart = System.currentTimeMillis()
        log("transcribe() start: meetingId=$meetingId file=${java.io.File(audioFilePath).name}")

        send(TranscriptionProgress("Preprocessing", 5))

        // Split stereo channels on the background dispatcher
        log("transcribe() splitting stereo channels...")
        val (micPath, sysPath) = withContext(transcriptionDispatcher) {
            val stereoPath = Path.of(audioFilePath)
            trySend(TranscriptionProgress("Splitting channels", 10))
            AudioPreprocessor.splitChannels(stereoPath)
        }
        log("transcribe() channels split: mic=${micPath.fileName} sys=${sysPath.fileName}")

        // Convert mic and sys channels to 16kHz in parallel
        send(TranscriptionProgress("Converting audio format", 20))
        log("transcribe() converting to 16kHz (parallel)...")
        val (micWhisperPath, sysWhisperPath) = withContext(transcriptionDispatcher) {
            coroutineScope {
                val micJob = async {
                    val out = micPath.resolveSibling(micPath.fileName.toString().replace(".wav", "_16k.wav"))
                    AudioPreprocessor.convertToWhisperFormat(micPath, out)
                    out
                }
                val sysJob = async {
                    val out = sysPath.resolveSibling(sysPath.fileName.toString().replace(".wav", "_16k.wav"))
                    AudioPreprocessor.convertToWhisperFormat(sysPath, out)
                    out
                }
                Pair(micJob.await(), sysJob.await())
            }
        }

        val tAfterPreprocess = System.currentTimeMillis()
        log("transcribe() preprocess complete in ${tAfterPreprocess - tStart}ms")

        // Use the pre-loaded service if available; otherwise create one and load now.
        val whisperService = sharedWhisperService ?: WhisperService()
        val ownsService = sharedWhisperService == null

        send(TranscriptionProgress("Loading model", 28))
        val tModelLoadStart = System.currentTimeMillis()
        // Reload if: model not yet loaded, or settings changed to a different model path.
        if (!whisperService.isLoaded || whisperService.loadedModelPath != modelPath) {
            log("transcribe() model load needed: isLoaded=${whisperService.isLoaded} current=${whisperService.loadedModelPath} requested=${java.io.File(modelPath).name}")
            withContext(Dispatchers.IO) { whisperService.loadModel(modelPath) }
        } else {
            log("transcribe() model already loaded: ${java.io.File(modelPath).name} (skipping reload)")
        }
        val modelLoadMs = System.currentTimeMillis() - tModelLoadStart

        // Run mic then sys sequentially - one WhisperState at a time.
        // whisper-large-v3 inference state is ~4 GB; running two in parallel doubles
        // peak memory to ~11 GB which, combined with Docker/JVM/Chrome overhead,
        // causes full swap and transcription can take 1+ hour for a 3-minute chunk.
        // Sequential uses half the peak memory and lets each inference use all cores,
        // which is typically faster in practice when the system is under memory pressure.
        val n = Runtime.getRuntime().availableProcessors()
        val threadsEach = (n - 1).coerceAtLeast(1)

        var micInferenceMs = 0L
        var sysInferenceMs = 0L

        // Split each channel into 3-minute chunks so results are persisted incrementally.
        // If transcription is interrupted, completed chunks are already saved.
        val micChunks = AudioPreprocessor.splitIntoChunks(micWhisperPath)
        val sysChunks = AudioPreprocessor.splitIntoChunks(sysWhisperPath)
        val totalChunks = micChunks.size
        val firstChunk = startFromChunkIdx.coerceIn(0, totalChunks)

        log("transcribe() $totalChunks chunks total, processing from chunk $firstChunk (skipping $firstChunk live chunks)")

        if (firstChunk > 0) {
            send(TranscriptionProgress("Transcribing tail (chunks ${firstChunk + 1}–$totalChunks)", 30))
        } else {
            send(TranscriptionProgress("Transcribing", 30))
        }

        for (chunkIdx in firstChunk until totalChunks) {
            val (micChunkPath, chunkOffsetMs) = micChunks[chunkIdx]
            val (sysChunkPath, _) = sysChunks[chunkIdx]
            val progressBase = 30 + chunkIdx * 55 / totalChunks
            val progressEnd  = 30 + (chunkIdx + 1) * 55 / totalChunks
            log("transcribe() chunk ${chunkIdx + 1}/$totalChunks starting: offset=${chunkOffsetMs}ms size=${micChunkPath.toFile().length()}B")
            val tChunk = System.currentTimeMillis()

            val micChunkSegs = withContext(transcriptionDispatcher) {
                val t = System.currentTimeMillis()
                val segs = whisperService.transcribe(
                    audioPath = micChunkPath.toString(),
                    meetingId = meetingId,
                    speakerLabel = "You",
                    nThreads = threadsEach,
                    chunkOffsetMs = chunkOffsetMs,
                    progressCallback = { p ->
                        trySend(TranscriptionProgress(
                            "Chunk ${chunkIdx + 1}/$totalChunks (mic)",
                            (progressBase + (p - 5) * (progressEnd - progressBase) / 2 / 90).coerceIn(progressBase, progressEnd),
                            currentChunk = chunkIdx + 1,
                            totalChunks = totalChunks,
                        ))
                    },
                )
                micInferenceMs += System.currentTimeMillis() - t
                segs
            }
            val sysChunkSegs = withContext(transcriptionDispatcher) {
                val t = System.currentTimeMillis()
                val segs = whisperService.transcribe(
                    audioPath = sysChunkPath.toString(),
                    meetingId = meetingId,
                    speakerLabel = "Caller",
                    nThreads = threadsEach,
                    chunkOffsetMs = chunkOffsetMs,
                    progressCallback = { p ->
                        trySend(TranscriptionProgress(
                            "Chunk ${chunkIdx + 1}/$totalChunks (sys)",
                            (progressBase + (progressEnd - progressBase) / 2 + (p - 5) * (progressEnd - progressBase) / 2 / 90).coerceIn(progressBase, progressEnd),
                            currentChunk = chunkIdx + 1,
                            totalChunks = totalChunks,
                        ))
                    },
                )
                sysInferenceMs += System.currentTimeMillis() - t
                segs
            }

            // Persist this chunk's segments immediately so partial results survive crashes
            val chunkSegments = (micChunkSegs + sysChunkSegs).sortedBy { it.startMs }
            withContext(Dispatchers.IO) {
                repository?.let { repo -> chunkSegments.forEach { repo.insertSegment(it) } }
            }
            log("transcribe() chunk ${chunkIdx + 1}/$totalChunks complete: ${chunkSegments.size} segments in ${System.currentTimeMillis() - tChunk}ms")
        }

        if (ownsService) whisperService.close()

        val tAfterTranscription = System.currentTimeMillis()

        val metrics = TranscriptionMetrics(
            preprocessMs = tAfterPreprocess - tStart,
            modelLoadMs = modelLoadMs,
            micInferenceMs = micInferenceMs,
            sysInferenceMs = sysInferenceMs,
            persistMs = 0L,  // persist is interleaved per-chunk; not meaningful as a total
        )
        log("transcribe() complete: total=${tAfterTranscription - tStart}ms preprocess=${metrics.preprocessMs}ms modelLoad=${metrics.modelLoadMs}ms micInference=${metrics.micInferenceMs}ms sysInference=${metrics.sysInferenceMs}ms")
        send(TranscriptionProgress("Complete", 100, metrics = metrics))
    }

    companion object {
        /** Must match RecordingSessionManager.CHUNK_DURATION_MS. */
        private const val LIVE_CHUNK_DURATION_MS = 3 * 60 * 1000L

        /**
         * Shared low-priority dispatcher for all transcription work.
         *
         * Sized at [Runtime.availableProcessors] so whisper inference can use every free
         * core. Thread priority is NORM_PRIORITY - 2 (below normal but above background)
         * so the UI and any other NORM_PRIORITY work preempts transcription under load
         * without starving it when the system is idle.
         *
         * Note: whisper.cpp spawns its own native pthreads for the hot path — this
         * dispatcher governs the JVM coroutine scheduling layer (audio loading, sample
         * conversion, segment filtering) and signals OS scheduler intent via thread
         * priority for the calling threads.
         */
        val transcriptionDispatcher: CoroutineDispatcher by lazy {
            val n = Runtime.getRuntime().availableProcessors()
            Executors.newFixedThreadPool(n) { runnable ->
                Thread(runnable, "whisper-worker").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 2
                }
            }.asCoroutineDispatcher()
        }

        fun log(msg: String) =
            System.err.println("[${java.time.Instant.now()}] [TranscriptionUseCase] $msg")
    }
}
