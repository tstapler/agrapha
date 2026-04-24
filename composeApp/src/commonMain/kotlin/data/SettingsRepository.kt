package com.meetingnotes.data

import com.meetingnotes.db.MeetingDatabase
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.LlmProvider as LlmProviderEnum
import com.meetingnotes.domain.model.WhisperModelSize

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
    }

    fun isOnboardingComplete(): Boolean =
        db.meetingQueries.getSetting(KEY_ONBOARDING_COMPLETE).executeAsOneOrNull() == "true"

    fun markOnboardingComplete() {
        db.meetingQueries.upsertSetting(KEY_ONBOARDING_COMPLETE, "true")
    }
}
