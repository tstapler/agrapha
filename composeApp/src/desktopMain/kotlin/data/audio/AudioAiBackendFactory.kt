package com.meetingnotes.data.audio

import com.meetingnotes.domain.audio.DiarizationBackend
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.platform.Platform
import com.meetingnotes.platform.PlatformInfo
import com.meetingnotes.transcription.PyannoteDiarizationBackend

/**
 * Creates the correct [DiarizationBackend] implementation based on [AppSettings.diarizationBackend].
 *
 * Selection logic:
 * - "fluida" → [FluidAudioDiarizationBackend] on macOS 14+; falls back to [PyannoteDiarizationBackend]
 *              on macOS < 14, Linux/Windows, or if the dylib cannot be loaded.
 * - "python" → [PyannoteDiarizationBackend] unconditionally.
 * - "onnx"   → [OnnxDiarizationBackend] (stub; [DiarizationBackend.diarize] throws [NotImplementedError]).
 * - unknown  → [PyannoteDiarizationBackend] with a warning log.
 *
 * Mirrors [com.meetingnotes.data.llm.LlmProviderFactory].
 */
object AudioAiBackendFactory {

    fun createDiarizationBackend(
        settings: AppSettings,
        platform: Platform = PlatformInfo,
    ): DiarizationBackend = when (settings.diarizationBackend) {
        "python" -> PyannoteDiarizationBackend()
        "onnx"   -> OnnxDiarizationBackend()
        "fluida" -> createFluidaOrFallback(platform)
        else -> {
            log("Unknown diarizationBackend '${settings.diarizationBackend}'; falling back to python")
            PyannoteDiarizationBackend()
        }
    }

    private fun createFluidaOrFallback(platform: Platform): DiarizationBackend {
        if (!platform.isMac() || platform.macOsMajorVersion() < 14) {
            log("FluidAudio requires macOS 14+; falling back to python backend")
            return PyannoteDiarizationBackend()
        }
        return runCatching { FluidAudioDiarizationBackend(platform) }.getOrElse { e ->
            log("FluidAudioDiarizationBackend load failed (${e.message}); falling back to python backend")
            PyannoteDiarizationBackend()
        }
    }

    private fun log(msg: String) = System.err.println("[AudioAiBackendFactory] $msg")
}
