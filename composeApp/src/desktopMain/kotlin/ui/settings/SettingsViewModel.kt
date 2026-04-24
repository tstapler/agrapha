package com.meetingnotes.ui.settings

import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.LlmProvider
import com.meetingnotes.domain.model.WhisperModelSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    data class UiState(
        val settings: AppSettings = AppSettings(),
        val validationErrors: Map<String, String> = emptyMap(),
        val saveSuccess: Boolean = false,
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = settingsRepository.load()
            // Warn immediately if the saved model file no longer exists (e.g. manually deleted)
            val initialErrors = if (loaded.whisperModelPath.isNotBlank() &&
                !File(loaded.whisperModelPath).exists()
            ) {
                mapOf("whisperModelPath" to "Active model file not found — select a different model")
            } else {
                emptyMap()
            }
            _state.value = UiState(settings = loaded, validationErrors = initialErrors, loading = false)
        }
    }

    fun onSettingsChange(settings: AppSettings) {
        _state.value = _state.value.copy(
            settings = settings,
            saveSuccess = false,
            validationErrors = emptyMap(),
        )
    }

    fun save() {
        val settings = _state.value.settings
        val errors = validate(settings)
        if (errors.isNotEmpty()) {
            _state.value = _state.value.copy(validationErrors = errors)
            return
        }
        scope.launch(Dispatchers.IO) {
            settingsRepository.save(settings)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(saveSuccess = true, validationErrors = emptyMap())
            }
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun validate(settings: AppSettings): Map<String, String> {
        val errors = mutableMapOf<String, String>()

        if (settings.whisperModelPath.isNotBlank() && !File(settings.whisperModelPath).exists()) {
            errors["whisperModelPath"] = "Model file not found"
        }

        if (settings.logseqWikiPath.isNotBlank()) {
            val wikiDir = File(settings.logseqWikiPath)
            if (!wikiDir.exists()) errors["logseqWikiPath"] = "Directory does not exist"
            else if (!wikiDir.isDirectory) errors["logseqWikiPath"] = "Path is not a directory"
        }

        if (settings.llmProvider != LlmProvider.OLLAMA && settings.llmApiKey.isNullOrBlank()) {
            errors["llmApiKey"] = "API key is required for ${settings.llmProvider.displayName}"
        }

        if (settings.diarizationEnabled && settings.huggingFaceToken.isBlank()) {
            errors["huggingFaceToken"] = "Hugging Face token is required to download the pyannote model"
        }

        return errors
    }
}
