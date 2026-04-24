package com.meetingnotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.MeetingRepository

/**
 * Root composable. Navigation state lives here.
 * Screens are wired in Story 6 (Menu Bar + Recording Session Management).
 */
@Composable
fun App(
    repository: MeetingRepository,
    storage: FileStorageService,
) {
    MaterialTheme {
        // TODO Story 6: replace with full AppNavigation composable
        PlaceholderScreen()
    }
}
