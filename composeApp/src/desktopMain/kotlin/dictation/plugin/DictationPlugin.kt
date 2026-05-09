package com.meetingnotes.dictation.plugin

import com.meetingnotes.audio.MicCaptureService
import com.meetingnotes.dictation.AutoDetectTextInjector
import com.meetingnotes.dictation.TextInjector
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.hotkey.HotkeyService
import com.meetingnotes.plugin.DictationMode
import com.meetingnotes.plugin.PluginException
import com.meetingnotes.plugin.SpeechOutputPlugin
import com.meetingnotes.transcription.WhisperService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Built-in [SpeechOutputPlugin] implementing three dictation modes.
 *
 * - [DictationMode.PUSH_TO_TALK]: mic recording triggered by UI button/shortcut (in-window focus);
 *   global hotkey is a Wayland limitation — see Settings for details.
 * - [DictationMode.FILE_TRANSCRIPTION]: offline file-to-text via Whisper.
 * - [DictationMode.LIVE_CAPTIONS]: always-on mic with streaming overlay.
 *
 * Registered via META-INF/services/com.meetingnotes.plugin.SpeechOutputPlugin.
 *
 * @param whisperService shared Whisper inference engine (model must be loaded by caller).
 * @param textInjector text injection backend; defaults to [AutoDetectTextInjector].
 */
class DictationPlugin(
    internal val whisperService: WhisperService? = null,
    internal val textInjector: TextInjector = AutoDetectTextInjector(),
    internal val hotkeyService: HotkeyService = HotkeyService(),
) : SpeechOutputPlugin {

    override val id: String = "com.agrapha.dictation"
    override val name: String = "Dictation"
    override val version: String = "1.0.0"
    override val supportedModes: Set<DictationMode> =
        setOf(DictationMode.PUSH_TO_TALK, DictationMode.FILE_TRANSCRIPTION, DictationMode.LIVE_CAPTIONS)

    // Active mode state
    private var activeMode: DictationMode? = null
    private var liveScope: CoroutineScope? = null
    private val _liveSegments = MutableStateFlow<List<String>>(emptyList())

    /** Exposed for LIVE_CAPTIONS consumers. */
    val liveSegments: StateFlow<List<String>> = _liveSegments.asStateFlow()

    override fun isAvailable(): Boolean {
        // Available on Linux (primary target) or wherever a text injector is reachable.
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return os.contains("linux") || textInjector.isAvailable()
    }

    override suspend fun activate(mode: DictationMode, config: Map<String, String>): Result<Unit> {
        activeMode = mode
        return when (mode) {
            DictationMode.PUSH_TO_TALK      -> activatePushToTalk(config)
            DictationMode.FILE_TRANSCRIPTION -> activateFileTranscription(config)
            DictationMode.LIVE_CAPTIONS      -> activateLiveCaptions(config)
        }
    }

    override suspend fun deactivate() {
        hotkeyService.stop()
        liveScope?.cancel()
        liveScope = null
        activeMode = null
        _liveSegments.value = emptyList()
    }

    // ── PUSH_TO_TALK ─────────────────────────────────────────────────────────

    private fun activatePushToTalk(config: Map<String, String>): Result<Unit> {
        if (!hotkeyService.isAvailable) {
            System.err.println(
                "[DictationPlugin] PUSH_TO_TALK: global hotkey unavailable " +
                "(${hotkeyService.backendDescription}). " +
                "Use triggerDictation() from the UI button instead."
            )
            // Not an error — the plugin still works via the UI trigger button
            return Result.success(Unit)
        }

        System.err.println(
            "[DictationPlugin] PUSH_TO_TALK: starting global hotkey listener " +
            "(backend: ${hotkeyService.backendDescription})"
        )

        liveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        liveScope!!.launch {
            hotkeyService.listen {
                System.err.println("[DictationPlugin] PUSH_TO_TALK hotkey fired — triggering dictation")
                triggerDictation()
            }
        }

        return Result.success(Unit)
    }

    /**
     * Perform a push-to-talk dictation roundtrip synchronously.
     *
     * Called by the recording UI when the user presses the dictation button.
     * Records up to [maxSeconds] seconds of audio (or until [stopDictation] is called),
     * transcribes with Whisper, and injects the result via [textInjector].
     *
     * @param maxSeconds maximum recording duration (default 10s)
     * @param meetingId ID to attach to transcript segments
     */
    suspend fun triggerDictation(
        maxSeconds: Int = 10,
        meetingId: String = "dictation-${System.currentTimeMillis()}",
    ): Result<String> = withContext(Dispatchers.IO) {
        val ws = whisperService
            ?: return@withContext Result.failure(
                PluginException("WhisperService not configured in DictationPlugin")
            )

        val micService = MicCaptureService()
        val samples = mutableListOf<Float>()
        val maxSamples = 16_000 * maxSeconds

        try {
            val job = launch {
                micService.captureFlow().collect { chunk ->
                    samples.addAll(chunk.toList())
                    if (samples.size >= maxSamples) cancel()
                }
            }
            job.join()
        } catch (_: CancellationException) {
            // expected when maxSamples reached
        } finally {
            micService.stop()
        }

        if (samples.isEmpty()) {
            return@withContext Result.failure(PluginException("No audio captured"))
        }

        // Write samples to a temp WAV and transcribe
        val tmpWav = File.createTempFile("dictation-", ".wav")
        try {
            writeWav(tmpWav, samples.toFloatArray())
            val segments = ws.transcribe(tmpWav.absolutePath, meetingId)
            val text = segments.joinToString(" ") { it.text.trim() }
            if (text.isBlank()) {
                return@withContext Result.failure(PluginException("Whisper returned empty transcript"))
            }

            val injectResult = textInjector.inject(text)
            if (injectResult.isFailure) {
                System.err.println("[DictationPlugin] inject failed: ${injectResult.exceptionOrNull()}")
            }
            return@withContext Result.success(text)
        } finally {
            tmpWav.delete()
        }
    }

    // ── FILE_TRANSCRIPTION ────────────────────────────────────────────────────

    private suspend fun activateFileTranscription(config: Map<String, String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            val ws = whisperService
                ?: return@withContext Result.failure(
                    PluginException("WhisperService not configured in DictationPlugin")
                )

            val inputPath = config["inputPath"]
                ?: return@withContext Result.failure(
                    PluginException("FILE_TRANSCRIPTION requires config[\"inputPath\"]")
                )

            val inputFile = File(inputPath)
            if (!inputFile.exists() || !inputFile.canRead()) {
                return@withContext Result.failure(
                    PluginException("Input file not found or not readable: $inputPath")
                )
            }

            val meetingId = config["meetingId"] ?: "file-${System.currentTimeMillis()}"
            val segments: List<TranscriptSegment>
            try {
                segments = ws.transcribe(inputPath, meetingId)
            } catch (e: Exception) {
                return@withContext Result.failure(PluginException("Transcription failed: ${e.message}", e))
            }

            val transcript = segments.joinToString("\n") { it.text.trim() }

            val outputPath = config["outputPath"]
            if (outputPath != null) {
                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()
                outFile.writeText(transcript)
                System.err.println("[DictationPlugin] transcript written to $outputPath")
            } else {
                println(transcript)
            }

            return@withContext Result.success(Unit)
        }

    // ── LIVE_CAPTIONS ─────────────────────────────────────────────────────────

    private fun activateLiveCaptions(config: Map<String, String>): Result<Unit> {
        val ws = whisperService
            ?: return Result.failure(PluginException("WhisperService not configured in DictationPlugin"))

        val maxSegments = config["maxSegments"]?.toIntOrNull() ?: 5
        val chunkMs = 3000L  // collect 3 seconds of audio per chunk
        val meetingId = "live-${System.currentTimeMillis()}"

        liveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _liveSegments.value = emptyList()

        liveScope!!.launch {
            val micService = MicCaptureService()
            val chunkSamples = mutableListOf<Float>()
            val samplesPerChunk = (16_000 * chunkMs / 1000).toInt()

            try {
                micService.captureFlow().collect { chunk ->
                    chunkSamples.addAll(chunk.toList())
                    if (chunkSamples.size >= samplesPerChunk) {
                        val samples = chunkSamples.toFloatArray()
                        chunkSamples.clear()

                        // Transcribe on a background thread without blocking mic collection
                        launch {
                            val tmpWav = File.createTempFile("live-caption-", ".wav")
                            try {
                                writeWav(tmpWav, samples)
                                val segments = ws.transcribe(tmpWav.absolutePath, meetingId)
                                val text = segments.joinToString(" ") { it.text.trim() }
                                if (text.isNotBlank()) {
                                    val current = _liveSegments.value.toMutableList()
                                    current.add(text)
                                    if (current.size > maxSegments) {
                                        current.removeAt(0)
                                    }
                                    _liveSegments.value = current
                                }
                            } catch (e: Exception) {
                                System.err.println("[DictationPlugin] live caption transcription error: $e")
                            } finally {
                                tmpWav.delete()
                            }
                        }
                    }
                }
            } finally {
                micService.stop()
            }
        }

        System.err.println("[DictationPlugin] LIVE_CAPTIONS activated")
        return Result.success(Unit)
    }

    // ── WAV helpers ───────────────────────────────────────────────────────────

    /** Write a minimal 16kHz mono 16-bit PCM WAV file from Float32 samples. */
    private fun writeWav(file: File, samples: FloatArray) {
        val dataSize = samples.size * 2
        val totalSize = 36 + dataSize

        file.outputStream().buffered().use { out ->
            fun writeInt(v: Int) { out.write(byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
            )) }
            fun writeShort(v: Int) { out.write(byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
            )) }

            out.write("RIFF".toByteArray())
            writeInt(totalSize)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeInt(16)       // chunk size
            writeShort(1)      // PCM
            writeShort(1)      // mono
            writeInt(16_000)   // sample rate
            writeInt(32_000)   // byte rate
            writeShort(2)      // block align
            writeShort(16)     // bits per sample
            out.write("data".toByteArray())
            writeInt(dataSize)

            for (s in samples) {
                val pcm = (s.coerceIn(-1f, 1f) * 32767).toInt()
                out.write((pcm and 0xFF).toByte().toInt())
                out.write(((pcm shr 8) and 0xFF).toByte().toInt())
            }
        }
    }
}
