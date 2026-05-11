package com.meetingnotes.data.audio

import com.meetingnotes.domain.audio.BackendUnavailableException
import com.meetingnotes.domain.audio.DiarizationBackend
import com.meetingnotes.domain.audio.DiarizationFailedException
import com.meetingnotes.domain.audio.DiarizationSegment
import com.meetingnotes.domain.audio.ModelDownloadRequiredException
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.platform.Platform
import com.meetingnotes.platform.PlatformInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/**
 * [DiarizationBackend] backed by the FluidAudio CoreML framework via JNI.
 *
 * Requires macOS 14+ and the compiled FluidDiarizationBridge dylib bundled as a classpath
 * resource. The [AudioAiBackendFactory] gates instantiation with a macOS version check, so
 * on macOS 12–13 or Linux/Windows this class is never constructed.
 *
 * Constructor throws [BackendUnavailableException] if the dylib cannot be found or loaded —
 * this is a runtime error, not a compile-time error, so the factory can catch it and fall back
 * to [com.meetingnotes.transcription.PyannoteDiarizationBackend].
 *
 * ANE concurrency: [diarize] holds [diarizeMutex] for the entire JNI call duration, ensuring
 * only one CoreML model runs on the Apple Neural Engine at a time (Story 8 acceptance criteria).
 *
 * See ADR-002 in project_plans/agrapha-fluid-audio/decisions/ for the DispatchSemaphore
 * JNI-to-Swift bridging pattern used in the native layer.
 *
 * **Bridge status:** JNI stubs are declared; Swift implementation is in Story 6.
 * Until the bridge is built, [loadBridge] will throw [BackendUnavailableException].
 */
class FluidAudioDiarizationBackend(
    private val platform: Platform = PlatformInfo,
) : DiarizationBackend {

    private val diarizeMutex = Mutex()

    init {
        loadBridge()
    }

    // ── DiarizationBackend ────────────────────────────────────────────────────

    override suspend fun isAvailable(): Boolean =
        platform.isMac() && platform.macOsMajorVersion() >= 14

    override suspend fun areModelsAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { nativeAreModelsAvailable() }.getOrDefault(false)
    }

    override suspend fun downloadModels(): Unit = withContext(Dispatchers.IO) {
        runCatching { nativeDownloadModels() }.onFailure { e ->
            throw BackendUnavailableException("FluidAudio model download failed: ${e.message}", e)
        }
    }

    override suspend fun diarize(
        audioFilePath: String,
        hfToken: String,
        maxSpeakers: Int?,
        timeoutMinutes: Long,
    ): List<DiarizationSegment> = withContext(Dispatchers.Default.limitedParallelism(1)) {
        if (!areModelsAvailable()) throw ModelDownloadRequiredException(
            "FluidAudio diarization models not downloaded. Open Settings to download."
        )
        diarizeMutex.withLock {
            val json = nativeDiarize(audioFilePath, maxSpeakers ?: 0, timeoutMinutes)
            parseResult(json)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    @Serializable
    private data class NativeSegment(val start: Double, val end: Double, val speaker: String)

    @Serializable
    private data class NativeError(val error: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseResult(raw: String): List<DiarizationSegment> {
        if (raw.trimStart().startsWith("{")) {
            val err = runCatching { json.decodeFromString<NativeError>(raw) }.getOrNull()
            if (err != null) throw DiarizationFailedException(err.error)
        }
        return json.decodeFromString<List<NativeSegment>>(raw)
            .map { DiarizationSegment(startSec = it.start, endSec = it.end, speaker = it.speaker) }
    }

    private fun loadBridge() {
        try {
            System.loadLibrary("FluidDiarizationBridge")
            return
        } catch (_: UnsatisfiedLinkError) {
            // Fall through to classpath-resource extraction.
        }

        val libName = "libFluidDiarizationBridge.dylib"
        val stream = FluidAudioDiarizationBackend::class.java.getResourceAsStream("/$libName")
            ?: throw BackendUnavailableException(
                "FluidDiarizationBridge not found in classpath. " +
                    "Build it: cd native/FluidDiarizationBridge && swift build -c release"
            )

        val tmpDir = Files.createTempDirectory("fluida-jni").toFile()
        val dest = File(tmpDir, libName)
        stream.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }

        try {
            System.load(dest.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            throw BackendUnavailableException(
                "Failed to load FluidDiarizationBridge: ${e.message}. " +
                    "Ensure the dylib targets the current OS and architecture.",
                e,
            )
        }
    }

    // ── JNI stubs (implemented by FluidDiarizationBridgeJNI.swift in Story 6) ─

    private external fun nativeAreModelsAvailable(): Boolean
    private external fun nativeDownloadModels()
    private external fun nativeDiarize(audioFilePath: String, maxSpeakers: Int, timeoutMinutes: Long): String
}
