package com.meetingnotes.transcription

import com.meetingnotes.domain.model.AppSettings

object TranscriptionBackendFactory {

    /**
     * IDs and display names of all backends available on the current platform.
     * Always has at least one entry (Whisper).
     */
    fun availableDescriptions(): List<Pair<String, String>> = buildList {
        add("whisper" to "Whisper (GGML, on-device)")
        if (AppleSpeechJniBridge.isAvailable()) {
            add("apple-speech" to "Apple Speech (on-device, no download)")
        }
        if (parakeetAvailable()) {
            add("parakeet" to "Parakeet-TDT-0.6B (ONNX, experimental)")
        }
    }

    /** Create the backend specified by [settings], falling back to Whisper if unavailable. */
    fun forSettings(settings: AppSettings): TranscriptionBackend =
        forId(settings.transcriptionBackend, settings.parakeetModelDir)

    /** Create a backend by ID. Falls back to [WhisperTranscriptionBackend] if not found or unavailable. */
    fun forId(id: String, parakeetModelDir: String = ""): TranscriptionBackend = when (id) {
        "apple-speech" -> runCatching {
            AppleSpeechBackend().takeIf { it.isAvailable }
        }.getOrNull() ?: WhisperTranscriptionBackend()
        "parakeet" -> runCatching {
            ParakeetOnnxBackend(modelDir = parakeetModelDir).also { it.prepare() }
        }.getOrElse { WhisperTranscriptionBackend() }
        else -> WhisperTranscriptionBackend()
    }

    private fun parakeetAvailable(): Boolean = runCatching {
        ParakeetOnnxBackend().isAvailable
    }.getOrDefault(false)
}
