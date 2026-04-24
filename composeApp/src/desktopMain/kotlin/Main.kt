package com.meetingnotes

import androidx.compose.ui.window.application
import com.meetingnotes.audio.ScreenCaptureJniBridge
import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.createDatabase
import com.meetingnotes.ui.AppRoot

fun main() = application {
    ScreenCaptureJniBridge.load()

    val storage = FileStorageService()
    storage.ensureDirectoriesExist()

    val db = createDatabase()
    val repository = MeetingRepository(db)
    val settingsRepository = SettingsRepository(db)

    AppRoot(
        repository = repository,
        settingsRepository = settingsRepository,
        storage = storage,
    )
}
