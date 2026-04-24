package com.meetingnotes.transcription

import com.meetingnotes.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * App-level singleton that manages Whisper model downloads in the background.
 *
 * Downloads are launched in this manager's own [CoroutineScope] so they survive
 * navigation between screens. Multiple models can download concurrently.
 *
 * When a download finishes successfully the model path is automatically written to
 * [SettingsRepository] so it becomes the active model immediately — no restart needed.
 *
 * Lifecycle: create once at app startup via `remember`; call [close] when the app exits.
 */
class ModelDownloadManager(
    private val modelsDir: File,
    private val settingsRepository: SettingsRepository,
) : Closeable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloader = WhisperModelDownloader()

    private val _states = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())

    /** Per-model download state keyed by [WhisperModelSpec.filename]. */
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    init {
        // Seed initial state: Done for models already on disk, Idle for the rest.
        _states.value = WHISPER_MODELS.associate { spec ->
            spec.filename to if (downloader.isAlreadyDownloaded(spec, modelsDir))
                ModelDownloadState.Done(File(modelsDir, spec.filename).absolutePath)
            else
                ModelDownloadState.Idle
        }
    }

    /** Start downloading [spec] in the background. No-op if already downloading or done. */
    fun download(spec: WhisperModelSpec) {
        val current = _states.value[spec.filename]
        if (current is ModelDownloadState.Downloading || current is ModelDownloadState.Verifying) return

        scope.launch {
            downloader.download(spec, modelsDir).collect { state ->
                _states.value = _states.value + (spec.filename to state)
                if (state is ModelDownloadState.Done) {
                    // Auto-select the newly downloaded model; a restart is not required.
                    withContext(Dispatchers.IO) {
                        val settings = settingsRepository.load()
                        settingsRepository.save(settings.copy(whisperModelPath = state.path))
                    }
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        downloader.close()
    }
}
