package com.meetingnotes

import androidx.compose.ui.window.application
import com.meetingnotes.audio.ScreenCaptureJniBridge
import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.createDatabase
import com.meetingnotes.dictation.plugin.DictationPlugin
import com.meetingnotes.transcription.TranscriptionBackendFactory
import com.meetingnotes.ui.AppRoot

fun main() {
    ScreenCaptureJniBridge.load()

    val storage = FileStorageService()
    storage.ensureDirectoriesExist()

    val db = createDatabase()
    val repository = MeetingRepository(db)
    val settingsRepository = SettingsRepository(db)

    // Load settings synchronously (SQLDelight is blocking on JVM) to select the backend.
    val settings = settingsRepository.load()
    val transcriptionBackend = TranscriptionBackendFactory.forSettings(settings)

    val dictationPlugin = DictationPlugin(transcriptionBackend = transcriptionBackend)

    application {
        AppRoot(
            repository = repository,
            settingsRepository = settingsRepository,
            storage = storage,
            dictationPlugin = dictationPlugin,
        )
    }
}
