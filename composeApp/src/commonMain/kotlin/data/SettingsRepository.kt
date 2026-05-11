package com.meetingnotes.data

import com.meetingnotes.db.MeetingDatabase
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.LlmProvider as LlmProviderEnum
import com.meetingnotes.domain.model.WhisperModelSize
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persistent key/value settings backed by SQLDelight AppSettingsStore.
 *
 * Default values are defined in [AppSettings]'s default constructor.
 */
class SettingsRepository(private val db: MeetingDatabase) {

    private companion object {
        const val KEY_WHISPER_MODEL_PATH = "whisperModelPath"
        const val KEY_WHISPER_MODEL_SIZE = "whisperModelSize"
        const val KEY_LLM_PROVIDER = "llmProvider"
        const val KEY_LLM_MODEL = "llmModel"
        const val KEY_LLM_API_KEY = "llmApiKey"
        const val KEY_LLM_BASE_URL = "llmBaseUrl"
        const val KEY_LOGSEQ_WIKI_PATH = "logseqWikiPath"
        const val KEY_ONBOARDING_COMPLETE = "onboardingComplete"
        const val KEY_AUTO_RECORD_ZOOM = "autoRecordZoom"
        const val KEY_AUTO_RECORD_GOOGLE_MEET = "autoRecordGoogleMeet"
        const val KEY_WHISPER_INITIAL_PROMPT = "whisperInitialPrompt"
        const val KEY_WHISPER_NO_SPEECH_THRESHOLD = "whisperNoSpeechThreshold"
        const val KEY_DIARIZATION_ENABLED = "diarizationEnabled"
        const val KEY_HUGGING_FACE_TOKEN = "huggingFaceToken"
        const val KEY_DIARIZATION_MAX_SPEAKERS = "diarizationMaxSpeakers"
        const val KEY_CORRECTION_ENABLED = "correctionEnabled"
        const val KEY_ENABLED_PLUGINS = "enabledPlugins"
        const val KEY_DIARIZATION_BACKEND = "diarizationBackend"
        const val KEY_TRANSCRIPTION_BACKEND = "transcriptionBackend"
        const val KEY_PARAKEET_MODEL_DIR = "parakeetModelDir"
        const val KEY_LIVE_CAPTIONS_ENABLED = "liveCaptionsEnabled"

        private val pluginsSerializer = MapSerializer(String.serializer(), Boolean.serializer())
        private val settingsJson = Json { ignoreUnknownKeys = true }
    }

    fun load(): AppSettings {
        val map = db.meetingQueries.getAllSettings().executeAsList()
            .associate { it.key to it.value_ }

        val defaults = AppSettings()
        return AppSettings(
            whisperModelPath = map[KEY_WHISPER_MODEL_PATH] ?: defaults.whisperModelPath,
            whisperModelSize = map[KEY_WHISPER_MODEL_SIZE]
                ?.let { runCatching { WhisperModelSize.valueOf(it) }.getOrNull() }
                ?: defaults.whisperModelSize,
            llmProvider = map[KEY_LLM_PROVIDER]
                ?.let { s -> runCatching { LlmProviderEnum.valueOf(s) }.getOrNull() }
                ?: defaults.llmProvider,
            llmModel = map[KEY_LLM_MODEL] ?: defaults.llmModel,
            llmApiKey = map[KEY_LLM_API_KEY],
            llmBaseUrl = map[KEY_LLM_BASE_URL] ?: defaults.llmBaseUrl,
            logseqWikiPath = map[KEY_LOGSEQ_WIKI_PATH] ?: defaults.logseqWikiPath,
            autoRecordZoom = map[KEY_AUTO_RECORD_ZOOM] == "true",
            autoRecordGoogleMeet = map[KEY_AUTO_RECORD_GOOGLE_MEET] == "true",
            whisperInitialPrompt = map[KEY_WHISPER_INITIAL_PROMPT] ?: defaults.whisperInitialPrompt,
            whisperNoSpeechThreshold = map[KEY_WHISPER_NO_SPEECH_THRESHOLD]
                ?.toFloatOrNull() ?: defaults.whisperNoSpeechThreshold,
            diarizationEnabled = map[KEY_DIARIZATION_ENABLED] == "true",
            huggingFaceToken = map[KEY_HUGGING_FACE_TOKEN] ?: defaults.huggingFaceToken,
            diarizationMaxSpeakers = map[KEY_DIARIZATION_MAX_SPEAKERS]
                ?.toIntOrNull() ?: defaults.diarizationMaxSpeakers,
            correctionEnabled = map[KEY_CORRECTION_ENABLED] == "true",
            enabledPlugins = map[KEY_ENABLED_PLUGINS]
                ?.let { runCatching { settingsJson.decodeFromString(pluginsSerializer, it) }.getOrNull() }
                ?: defaults.enabledPlugins,
            diarizationBackend = map[KEY_DIARIZATION_BACKEND] ?: defaults.diarizationBackend,
            transcriptionBackend = map[KEY_TRANSCRIPTION_BACKEND] ?: defaults.transcriptionBackend,
            parakeetModelDir = map[KEY_PARAKEET_MODEL_DIR] ?: defaults.parakeetModelDir,
            liveCaptionsEnabled = map[KEY_LIVE_CAPTIONS_ENABLED] == "true",
        )
    }

    fun save(settings: AppSettings) {
        db.meetingQueries.upsertSetting(KEY_WHISPER_MODEL_PATH, settings.whisperModelPath)
        db.meetingQueries.upsertSetting(KEY_WHISPER_MODEL_SIZE, settings.whisperModelSize.name)
        db.meetingQueries.upsertSetting(KEY_LLM_PROVIDER, settings.llmProvider.name)
        db.meetingQueries.upsertSetting(KEY_LLM_MODEL, settings.llmModel)
        settings.llmApiKey?.let { db.meetingQueries.upsertSetting(KEY_LLM_API_KEY, it) }
        db.meetingQueries.upsertSetting(KEY_LLM_BASE_URL, settings.llmBaseUrl)
        db.meetingQueries.upsertSetting(KEY_LOGSEQ_WIKI_PATH, settings.logseqWikiPath)
        db.meetingQueries.upsertSetting(KEY_AUTO_RECORD_ZOOM, settings.autoRecordZoom.toString())
        db.meetingQueries.upsertSetting(KEY_AUTO_RECORD_GOOGLE_MEET, settings.autoRecordGoogleMeet.toString())
        db.meetingQueries.upsertSetting(KEY_WHISPER_INITIAL_PROMPT, settings.whisperInitialPrompt)
        db.meetingQueries.upsertSetting(KEY_WHISPER_NO_SPEECH_THRESHOLD, settings.whisperNoSpeechThreshold.toString())
        db.meetingQueries.upsertSetting(KEY_DIARIZATION_ENABLED, settings.diarizationEnabled.toString())
        db.meetingQueries.upsertSetting(KEY_HUGGING_FACE_TOKEN, settings.huggingFaceToken)
        db.meetingQueries.upsertSetting(KEY_DIARIZATION_MAX_SPEAKERS, settings.diarizationMaxSpeakers.toString())
        db.meetingQueries.upsertSetting(KEY_CORRECTION_ENABLED, settings.correctionEnabled.toString())
        db.meetingQueries.upsertSetting(KEY_ENABLED_PLUGINS, settingsJson.encodeToString(pluginsSerializer, settings.enabledPlugins))
        db.meetingQueries.upsertSetting(KEY_DIARIZATION_BACKEND, settings.diarizationBackend)
        db.meetingQueries.upsertSetting(KEY_TRANSCRIPTION_BACKEND, settings.transcriptionBackend)
        db.meetingQueries.upsertSetting(KEY_PARAKEET_MODEL_DIR, settings.parakeetModelDir)
        db.meetingQueries.upsertSetting(KEY_LIVE_CAPTIONS_ENABLED, settings.liveCaptionsEnabled.toString())
    }

    fun isOnboardingComplete(): Boolean =
        db.meetingQueries.getSetting(KEY_ONBOARDING_COMPLETE).executeAsOneOrNull() == "true"

    fun markOnboardingComplete() {
        db.meetingQueries.upsertSetting(KEY_ONBOARDING_COMPLETE, "true")
    }
}
