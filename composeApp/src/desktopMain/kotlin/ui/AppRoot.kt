package com.meetingnotes.ui

import androidx.compose.runtime.*
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import com.meetingnotes.audio.MeetingDetector
import com.meetingnotes.transcription.ModelDownloadManager
import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.domain.PipelineQueueExecutor
import com.meetingnotes.domain.model.RecordingState
import com.meetingnotes.ui.history.HistoryScreen
import com.meetingnotes.ui.history.HistoryViewModel
import com.meetingnotes.ui.onboarding.OnboardingScreen
import com.meetingnotes.ui.recording.RecordingScreen
import com.meetingnotes.ui.recording.RecordingViewModel
import com.meetingnotes.ui.settings.SettingsScreen
import com.meetingnotes.ui.settings.SettingsViewModel
import com.meetingnotes.ui.transcript.TranscriptScreen
import com.meetingnotes.ui.transcript.TranscriptViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root composable + window management for the desktop app.
 *
 * Handles:
 * - First-run onboarding check
 * - State-driven navigation
 * - Menu bar icon synchronization with recording state
 */
@Composable
fun ApplicationScope.AppRoot(
    repository: MeetingRepository,
    settingsRepository: SettingsRepository,
    storage: FileStorageService,
) {
    val appScope = rememberCoroutineScope()
    var windowVisible by remember { mutableStateOf(true) }
    var destination by remember { mutableStateOf<AppDestination>(AppDestination.Recording) }

    // Background model downloader — survives navigation
    val modelDownloadManager = remember {
        ModelDownloadManager(
            modelsDir = java.io.File(storage.getModelsDir()),
            settingsRepository = settingsRepository,
        )
    }
    DisposableEffect(Unit) { onDispose { modelDownloadManager.close() } }

    // Meeting detector (always running; auto-record logic is gated by settings)
    val meetingDetector = remember { MeetingDetector() }
    DisposableEffect(Unit) { onDispose { meetingDetector.close() } }
    val activeMeeting by meetingDetector.activeMeeting.collectAsState()

    // Auto-record settings — reloaded whenever the user returns to the Recording screen
    // so changes saved in SettingsScreen take effect immediately.
    var autoRecordZoom by remember { mutableStateOf(false) }
    var autoRecordGoogleMeet by remember { mutableStateOf(false) }

    // Check onboarding on first composition, then run retention cleanup
    var onboardingComplete by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        onboardingComplete = settingsRepository.isOnboardingComplete()
        if (onboardingComplete == false) {
            destination = AppDestination.Onboarding
        }
        // Delete audio files older than the configured retention period.
        // retentionDays == 0 means "keep forever" — skip cleanup entirely.
        withContext(Dispatchers.IO) {
            val settings = settingsRepository.load()
            val retentionDays = settings.recordingRetentionDays
            if (retentionDays > 0) {
                val cutoffMs = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
                repository.getMeetingsBefore(cutoffMs).forEach { meeting ->
                    try {
                        storage.deleteAudioFile(meeting.id)
                        repository.clearAudioFilePath(meeting.id)
                    } catch (_: Exception) {
                        // Don't let one failed deletion abort the rest of cleanup
                    }
                }
            }
        }
    }

    // Reload auto-record settings whenever returning to the Recording screen
    LaunchedEffect(destination) {
        if (destination is AppDestination.Recording) {
            val settings = withContext(Dispatchers.IO) { settingsRepository.load() }
            autoRecordZoom = settings.autoRecordZoom
            autoRecordGoogleMeet = settings.autoRecordGoogleMeet
        }
    }

    val executor = remember {
        PipelineQueueExecutor(repository = repository, settingsRepository = settingsRepository)
    }

    val recordingViewModel = remember {
        RecordingViewModel(
            repository = repository,
            settingsRepository = settingsRepository,
            storage = storage,
            scope = appScope,
            executor = executor,
        )
    }

    // Sync menu bar icon with recording state
    val recordingState by recordingViewModel.state.collectAsState()
    val menuBar = remember {
        MenuBarManager(
            onStartRecording = { recordingViewModel.startRecording(); windowVisible = true },
            onStopRecording = { recordingViewModel.stopRecording() },
            onShowWindow = { windowVisible = true },
            onQuit = {
                CoroutineScope(Dispatchers.IO).launch { recordingViewModel.stopRecording() }
                exitApplication()
            },
        ).also { it.install() }
    }

    LaunchedEffect(recordingState) {
        menuBar.setRecordingState(recordingState is RecordingState.Recording)
        // Processing (transcription/summarization/export) now runs in PipelineQueueExecutor
        // in the background, so only Stopping triggers a window focus (recording just ended).
        if (recordingState is RecordingState.Stopping) {
            windowVisible = true
            destination = AppDestination.Recording
        }
    }

    // Auto-record: start/stop recording when a meeting is detected (if enabled in settings)
    LaunchedEffect(autoRecordZoom, autoRecordGoogleMeet) {
        meetingDetector.activeMeeting.collect { meeting ->
            val shouldAutoRecord = when (meeting?.source) {
                com.meetingnotes.audio.MeetingSource.ZOOM -> autoRecordZoom
                com.meetingnotes.audio.MeetingSource.GOOGLE_MEET -> autoRecordGoogleMeet
                null -> false
            }
            val currentState = recordingViewModel.state.value
            if (shouldAutoRecord && meeting != null && currentState is RecordingState.Idle) {
                recordingViewModel.startRecording()
            } else if (meeting == null && currentState is RecordingState.Recording) {
                // Only auto-stop if the recording was likely started by auto-record
                // (no way to distinguish, so stop whenever a meeting ends)
                val eitherEnabled = autoRecordZoom || autoRecordGoogleMeet
                if (eitherEnabled) recordingViewModel.stopRecording()
            }
        }
    }

    val navigate: (AppDestination) -> Unit = { dest ->
        destination = dest
        windowVisible = true
    }

    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "Agrapha",
    ) {
        AgrapaTheme {
            when (val dest = destination) {
                is AppDestination.Onboarding -> OnboardingScreen(
                    settingsRepository = settingsRepository,
                    storage = storage,
                    scope = appScope,
                    modelDownloadManager = modelDownloadManager,
                    onComplete = { destination = AppDestination.Recording },
                    onNavigate = navigate,
                )
                is AppDestination.Recording -> RecordingScreen(
                    viewModel = recordingViewModel,
                    onNavigate = navigate,
                    activeMeeting = activeMeeting,
                    autoRecordEnabled = autoRecordZoom || autoRecordGoogleMeet,
                )
                is AppDestination.History -> {
                    val vm = remember {
                        HistoryViewModel(repository = repository, scope = appScope)
                    }
                    LaunchedEffect(dest) { vm.refresh() }
                    HistoryScreen(viewModel = vm, onNavigate = navigate)
                }
                is AppDestination.Transcript -> {
                    val vm = remember(dest.meetingId) {
                        TranscriptViewModel(
                            meetingId = dest.meetingId,
                            repository = repository,
                            settingsRepository = settingsRepository,
                            scope = appScope,
                            executor = executor,
                        )
                    }
                    TranscriptScreen(viewModel = vm, onNavigate = navigate)
                }
                is AppDestination.Settings -> {
                    val vm = remember {
                        SettingsViewModel(settingsRepository = settingsRepository, scope = appScope)
                    }
                    SettingsScreen(
                        viewModel = vm,
                        storage = storage,
                        modelDownloadManager = modelDownloadManager,
                        onNavigate = navigate,
                    )
                }
            }
        }
    }
}
