package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment
import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.util.UUID

/**
 * Wraps whisper-jni to transcribe a 16kHz mono WAV into [TranscriptSegment] objects.
 *
 * Actual API (verified via javap on whisper-jni 1.6.1):
 *  - whisper.initNoState(Path) → WhisperContext  (model weights only, no inference state)
 *  - whisper.initState(ctx) → WhisperState       (lightweight per-inference state)
 *  - WhisperFullParams(WhisperSamplingStrategy.GREEDY) — direct constructor
 *  - whisper.fullWithState(ctx, state, params, floatArray, numSamples) → 0 on success
 *  - whisper.fullNSegmentsFromState(state) → Int
 *  - whisper.fullGetSegmentTimestamp0FromState(state, i) → Long (centiseconds)
 *  - whisper.fullGetSegmentTimestamp1FromState(state, i) → Long (centiseconds)
 *  - whisper.fullGetSegmentTextFromState(state, i) → String
 *  - whisper.free(state) — releases inference state
 *  - ctx.close() — releases model weights
 *
 * Using initNoState/initState means the model weights are loaded ONCE and shared across
 * concurrent transcribe() calls — each call creates its own WhisperState so parallel
 * invocations are safe.
 */
class WhisperService : Closeable {

    private var whisper: WhisperJNI? = null
    private var sharedCtx: WhisperContext? = null

    /**
     * Read-write lock protecting [sharedCtx].
     *
     * - [transcribe] acquires the read lock: multiple concurrent transcriptions are fine
     *   because each creates its own WhisperState from the shared context.
     * - [loadModel] acquires the write lock: waits for all active transcriptions to finish
     *   before closing and replacing [sharedCtx]. Without this, a live-chunk transcription
     *   running on the transcriptionDispatcher could use a freed native context when
     *   the post-recording pipeline calls loadModel(), causing a native crash/hang.
     */
    private val ctxLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /** True once [loadModel] has been called successfully. */
    val isLoaded: Boolean get() = sharedCtx != null

    /** Backend used for inference: "CoreML", "CPU", or "unknown" before first transcription. */
    val backend: String get() = detectedBackend

    /** Absolute path of the model currently loaded, or null if no model is loaded. */
    var loadedModelPath: String? = null
        private set

    /** Load the GGML model at [modelPath]. Must be called before [transcribe]. */
    fun loadModel(modelPath: String) {
        log("loadModel() acquiring write lock for: ${File(modelPath).name}")
        ctxLock.writeLock().lock()
        try {
            log("loadModel() write lock acquired, loading ${File(modelPath).name} (${File(modelPath).length() / 1024 / 1024} MB)...")
            require(File(modelPath).exists()) { "Whisper model not found: $modelPath" }
            if (whisper == null) {
                loadLibraryOnce()
                whisper = WhisperJNI()
            }
            sharedCtx?.close()
            sharedCtx = whisper!!.initNoState(Path.of(modelPath))
            checkNotNull(sharedCtx) { "Failed to initialise Whisper context from: $modelPath" }
            loadedModelPath = modelPath
            log("loadModel() complete for: ${File(modelPath).name}")
        } finally {
            ctxLock.writeLock().unlock()
        }
    }

    /**
     * Transcribe [audioPath] (16kHz mono 16-bit PCM WAV) into [TranscriptSegment] objects.
     *
     * Safe to call concurrently on the same instance — each invocation creates and frees its
     * own [io.github.givimad.whisperjni.WhisperState] so inference states never collide.
     *
     * @param audioPath Path to a pre-processed 16kHz mono WAV
     * @param meetingId ID to attach to each segment
     * @param speakerLabel Fixed speaker label for all segments ("You" or "Caller")
     * @param nThreads Override the number of CPU threads used for inference. Defaults to
     *                 all available processors minus one. Pass a lower value when running
     *                 two channels in parallel to avoid thread contention.
     * @param progressCallback Called with 0–100 progress estimate
     */
    fun transcribe(
        audioPath: String,
        meetingId: String,
        speakerLabel: String? = null,
        nThreads: Int? = null,
        chunkOffsetMs: Long = 0L,
        progressCallback: ((Int) -> Unit)? = null,
        initialPrompt: String = "This is a software engineering meeting.",
        noSpeechThreshold: Float = 0.7f,
    ): List<TranscriptSegment> {
        log("transcribe() acquiring read lock: speaker=$speakerLabel offset=${chunkOffsetMs}ms file=${File(audioPath).name}")
        ctxLock.readLock().lock()
        try {
        return transcribeUnderLock(audioPath, meetingId, speakerLabel, nThreads, chunkOffsetMs, progressCallback, initialPrompt, noSpeechThreshold)
        } finally {
            ctxLock.readLock().unlock()
            log("transcribe() read lock released: speaker=$speakerLabel offset=${chunkOffsetMs}ms")
        }
    }

    private fun transcribeUnderLock(
        audioPath: String,
        meetingId: String,
        speakerLabel: String?,
        nThreads: Int?,
        chunkOffsetMs: Long,
        progressCallback: ((Int) -> Unit)?,
        initialPrompt: String,
        noSpeechThreshold: Float,
    ): List<TranscriptSegment> {
        val whisper = checkNotNull(whisper) { "WhisperJNI not initialised. Call loadModel() first." }
        val ctx = checkNotNull(sharedCtx) { "Model not loaded. Call loadModel() first." }

        progressCallback?.invoke(5)
        log("transcribe() loading audio samples: ${File(audioPath).name}")
        val t0 = System.currentTimeMillis()
        val samples = loadAudioSamples(audioPath)
        log("transcribe() audio loaded: ${samples.size} samples in ${System.currentTimeMillis() - t0}ms")
        progressCallback?.invoke(20)

        // Construct params directly (no factory method — confirmed via javap)
        val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY)
        params.language = "en"
        params.nThreads = nThreads ?: (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        params.initialPrompt = initialPrompt
        params.noSpeechThold = noSpeechThreshold
        params.suppressBlank = true
        params.printProgress = false
        params.printRealtime = false
        params.printTimestamps = false

        require(samples.isNotEmpty()) { "Empty audio buffer for $audioPath - skipping inference" }
        require(samples.size >= 1600) { "Audio buffer too short (${samples.size} samples, min 1600 = 100ms at 16kHz)" }

        // Create a fresh inference state — released in finally so memory is reclaimed promptly
        val state = whisper.initState(ctx)
        try {
            log("transcribe() starting inference: speaker=$speakerLabel offset=${chunkOffsetMs}ms threads=${params.nThreads} samples=${samples.size}")
            val tInfer = System.currentTimeMillis()
            val rc = whisper.fullWithState(ctx, state, params, samples, samples.size)
            log("transcribe() inference complete: speaker=$speakerLabel took ${System.currentTimeMillis() - tInfer}ms rc=$rc")
            check(rc == 0) { "whisper.fullWithState() failed with error code $rc" }
            progressCallback?.invoke(85)

            val nSegments = whisper.fullNSegmentsFromState(state)
            val segments = mutableListOf<TranscriptSegment>()
            var filteredNeg = 0; var filteredShort = 0; var filteredDuration = 0; var filteredHallu = 0; var filteredRepeat = 0

            for (i in 0 until nSegments) {
                // Strip control characters (whisper occasionally emits binary garbage)
                val text = whisper.fullGetSegmentTextFromState(state, i)
                    .filter { it.code >= 32 || it == '\n' }.trim()
                // Timestamps are centiseconds (1/100 s) → × 10 = milliseconds, then add chunk offset
                val startMs = whisper.fullGetSegmentTimestamp0FromState(state, i) * 10L + chunkOffsetMs
                val endMs = whisper.fullGetSegmentTimestamp1FromState(state, i) * 10L + chunkOffsetMs
                val durationMs = endMs - startMs

                if (startMs < 0L)               { filteredNeg++; continue }
                if (text.length < 3)            { filteredShort++; continue }
                if (durationMs < 200L)          { filteredDuration++; continue }
                if (isKnownHallucination(text)) { filteredHallu++; continue }
                if (isRepetitionLoop(text)) { filteredRepeat++; continue }

                segments += TranscriptSegment(
                    id = UUID.randomUUID().toString(),
                    meetingId = meetingId,
                    speakerLabel = speakerLabel,
                    startMs = startMs,
                    endMs = endMs,
                    text = text,
                )
            }

            log("transcribe() filter stats: raw=$nSegments neg=$filteredNeg short=$filteredShort " +
                "duration=$filteredDuration hallu=$filteredHallu filteredRepeat=$filteredRepeat kept=${segments.size} " +
                "speaker=$speakerLabel offset=${chunkOffsetMs}ms")
            // When all segments were filtered, sample the first few raw texts to help diagnose
            if (segments.isEmpty() && nSegments > 0) {
                for (i in 0 until minOf(3, nSegments)) {
                    val t = whisper.fullGetSegmentTextFromState(state, i).trim()
                    log("transcribe() raw[$i]: '$t'")
                }
            }
            progressCallback?.invoke(95)
            return segments
        } finally {
            whisper.free(state)
        }
    }

    /** Release the model weights context. Always call when the service is no longer needed. */
    override fun close() {
        log("close() acquiring write lock")
        ctxLock.writeLock().lock()
        try {
            sharedCtx?.close()
            sharedCtx = null
            loadedModelPath = null
            log("close() complete")
        } finally {
            ctxLock.writeLock().unlock()
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun log(msg: String) =
        System.err.println("[${java.time.Instant.now()}] [WhisperService] $msg")

    private fun loadAudioSamples(audioPath: String): FloatArray {
        val file = File(audioPath)
        val headerSize = 44
        val sampleCount = ((file.length() - headerSize) / 2).toInt().coerceAtLeast(0)
        val samples = FloatArray(sampleCount)
        file.inputStream().buffered().use { stream ->
            // Skip the 44-byte WAV header
            var skipped = 0L
            while (skipped < headerSize) skipped += stream.skip(headerSize - skipped)
            // Decode 16-bit little-endian PCM directly into FloatArray — no intermediate ByteArray
            val buf = ByteArray(65536)  // 64 KB / 2 bytes per sample = 32K samples per read
            var idx = 0
            while (idx < sampleCount) {
                val toRead = minOf(buf.size, (sampleCount - idx) * 2)
                var read = 0
                while (read < toRead) {
                    val r = stream.read(buf, read, toRead - read)
                    if (r == -1) break
                    read += r
                }
                val samplesRead = read / 2
                for (i in 0 until samplesRead) {
                    val lo = buf[i * 2].toInt() and 0xFF
                    val hi = buf[i * 2 + 1].toInt()
                    samples[idx++] = ((hi shl 8) or lo) / 32768f
                }
                if (samplesRead == 0) break
            }
        }
        return samples
    }

    private fun isRepetitionLoop(text: String): Boolean {
        val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 3) return false
        // Check trigrams: any 3-word window appearing 3+ times indicates a loop
        val trigrams = mutableMapOf<String, Int>()
        for (i in 0..words.size - 3) {
            val trigram = "${words[i]} ${words[i+1]} ${words[i+2]}"
            val count = trigrams.getOrDefault(trigram, 0) + 1
            trigrams[trigram] = count
            if (count >= 3) return true
        }
        // Check long verbatim repeat: text normalized is >90% same as first half
        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
        val half = normalized.substring(0, normalized.length / 2)
        if (half.isNotEmpty() && normalized.startsWith(half) && normalized.length >= 20) {
            val overlap = half.length.toDouble() / normalized.length
            if (overlap > 0.45) return true  // first half covers >45% → near-duplicate repeat
        }
        return false
    }

    private fun isKnownHallucination(text: String): Boolean =
        text.lowercase().trim().trimEnd('.') in HALLUCINATION_PHRASES

    companion object {
        private val HALLUCINATION_PHRASES = setOf(
            "thank you", "thanks", "thank you for watching", "thanks for watching",
            "you", "bye", "bye bye", "goodbye", "see you later", "you're welcome",
            "uh", "um", "hmm", "hm",
            "subtitles by the amara.org community",
            "transcribed by https://otter.ai",
        )

        /**
         * Load the whisper-jni native library exactly once, thread-safely.
         *
         * Preference order:
         *  1. libwhisperjni-coreml.dylib bundled as a classpath resource (built by
         *     native/WhisperCoreML/make with -DWHISPER_COREML=1). When present this gives
         *     3–4× ANE acceleration on Apple Silicon with no API changes.
         *  2. WhisperJNI.loadLibrary() — whisper-jni's bundled CPU-only dylib (fallback).
         *
         * Without the double-checked locking, parallel channel transcription races inside
         * [WhisperJNI.loadLibrary] — both threads simultaneously extract the dylib to the
         * same temp dir, one writes a truncated file, and the loader crashes with:
         *   UnsatisfiedLinkError: segment '__LINKEDIT' load command content extends beyond end of file
         *
         * On [UnsatisfiedLinkError] in the CPU fallback path, we wipe all whisper-jni temp
         * dirs and retry once — recovering from stale corrupt extractions left by a previous
         * JVM crash.
         */
        @Volatile private var libraryLoaded = false
        @Volatile private var detectedBackend: String = "unknown"

        private val loadLock = Any()

        private fun loadLibraryOnce() {
            if (libraryLoaded) return
            synchronized(loadLock) {
                if (libraryLoaded) return
                // Prefer CoreML dylib (built by native/WhisperCoreML/make, bundled as resource).
                val coremlLoaded = runCatching {
                    val stream = WhisperService::class.java.getResourceAsStream("/libwhisperjni-coreml.dylib")
                    if (stream != null) {
                        val tmpDir = java.nio.file.Files.createTempDirectory("meeting-notes-whisper-coreml").toFile()
                        val dest = File(tmpDir, "libwhisperjni-coreml.dylib")
                        stream.use { it.copyTo(dest.outputStream()) }
                        System.load(dest.absolutePath)
                        System.err.println("[WhisperService] CoreML dylib loaded: libwhisperjni-coreml.dylib")
                        System.err.println("[WhisperService] backend=CoreML")
                        detectedBackend = "CoreML"
                        true
                    } else false
                }.getOrDefault(false)

                if (!coremlLoaded) {
                    // Fallback: whisper-jni's built-in CPU-only dylib.
                    System.err.println("[WhisperService] backend=CPU")
                    detectedBackend = "CPU"
                    try {
                        WhisperJNI.loadLibrary()
                    } catch (_: UnsatisfiedLinkError) {
                        // Corrupt or partial extraction in temp dir — wipe and retry once.
                        File(System.getProperty("java.io.tmpdir"))
                            .listFiles { f -> f.isDirectory && f.name.startsWith("whisper-jni-") }
                            ?.forEach { it.deleteRecursively() }
                        WhisperJNI.loadLibrary()
                    }
                }
                libraryLoaded = true
            }
        }
    }
}
