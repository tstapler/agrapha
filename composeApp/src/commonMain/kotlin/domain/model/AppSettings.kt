package com.meetingnotes.domain.model

import kotlinx.serialization.Serializable

/** Whisper model size — larger = more accurate but slower. */
@Serializable
enum class WhisperModelSize(val displayName: String, val fileName: String, val sizeBytes: Long) {
    TINY("Tiny (75 MB)", "ggml-tiny.bin", 75_000_000L),
    BASE("Base (142 MB)", "ggml-base.bin", 142_000_000L),
    SMALL("Small (465 MB)", "ggml-small.bin", 465_000_000L),
    MEDIUM("Medium (1.5 GB)", "ggml-medium.bin", 1_500_000_000L),
}

/** Which LLM backend to use for summarisation. */
@Serializable
enum class LlmProvider(val displayName: String) {
    OLLAMA("Ollama (local)"),
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
}

/**
 * Persistent application settings.
 *
 * @param whisperModelPath Absolute path to the Whisper GGML model file
 * @param whisperModelSize Enum variant corresponding to the loaded model
 * @param llmProvider Which LLM backend to use
 * @param llmModel Model name (e.g. "llama3.2", "gpt-4o", "claude-sonnet-4-6")
 * @param llmApiKey API key — null for Ollama
 * @param llmBaseUrl Base URL (e.g. "http://localhost:11434" for Ollama)
 * @param logseqWikiPath Absolute path to the Logseq wiki root directory
 */
@Serializable
data class AppSettings(
    val whisperModelPath: String = "",
    val whisperModelSize: WhisperModelSize = WhisperModelSize.SMALL,
    val llmProvider: LlmProvider = LlmProvider.OLLAMA,
    val llmModel: String = "llama3.2",
    val llmApiKey: String? = null,
    val llmBaseUrl: String = "http://localhost:11434",
    val logseqWikiPath: String = "",
    /** How long to keep raw audio recordings on disk (days). 0 = keep forever. */
    val recordingRetentionDays: Int = 30,
    /** Automatically start/stop recording when a Zoom meeting is detected. */
    val autoRecordZoom: Boolean = false,
    /** Automatically start/stop recording when a Google Meet meeting is detected. */
    val autoRecordGoogleMeet: Boolean = false,
    /** Initial prompt sent to Whisper to bias transcription towards tech/engineering vocabulary. */
    val whisperInitialPrompt: String = "This is a software engineering meeting.",
    /** Whisper no-speech probability threshold; segments above this are dropped. */
    val whisperNoSpeechThreshold: Float = 0.7f,
    /** Enable post-hoc speaker diarization via Python/pyannote sidecar. */
    val diarizationEnabled: Boolean = false,
    /** Hugging Face access token required by pyannote model download. */
    val huggingFaceToken: String = "",
    /** Maximum number of speakers pyannote will detect. 0 = auto-detect. */
    val diarizationMaxSpeakers: Int = 0,
    /** Enable LLM-backed transcript error correction via Ollama after transcription. */
    val correctionEnabled: Boolean = false,
)
