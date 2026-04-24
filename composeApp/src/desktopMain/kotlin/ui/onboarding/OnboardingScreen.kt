package com.meetingnotes.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meetingnotes.audio.ScreenCaptureJniBridge
import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.transcription.ModelDownloadManager
import com.meetingnotes.transcription.ModelDownloadState
import com.meetingnotes.transcription.WhisperModelSpec
import com.meetingnotes.transcription.WHISPER_MODELS
import com.meetingnotes.ui.AppDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * First-run onboarding wizard:
 *   Step 0 — Screen recording permission (ScreenCaptureKit TCC)
 *   Step 1 — Microphone permission
 *   Step 2 — Whisper model download (with SHA-256 validation)
 *   Step 3 — Logseq wiki path
 */
@Composable
fun OnboardingScreen(
    settingsRepository: SettingsRepository,
    storage: FileStorageService,
    scope: CoroutineScope,
    modelDownloadManager: ModelDownloadManager,
    onComplete: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    // Start at step 1 (skip permission) if screen recording is already granted.
    var step by remember {
        mutableIntStateOf(
            try { if (ScreenCaptureJniBridge.nativeCheckPermission()) 1 else 0 }
            catch (_: Throwable) { 0 }
        )
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Welcome to Meeting Notes", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Let's get you set up. This takes about 2 minutes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LinearProgressIndicator(
            progress = { (step + 1) / 4f },
            modifier = Modifier.fillMaxWidth(),
        )

        when (step) {
            0 -> PermissionStep(
                title = "Screen Recording Permission",
                description = "Meeting Notes needs Screen Recording permission to capture " +
                    "system audio from Zoom, Teams, or any other app.\n\n" +
                    "Click 'Request Permission' — macOS will show a permission dialog.\n" +
                    "After granting, come back and click 'Continue'.",
                actionLabel = "Request Permission",
                onAction = {
                    try { ScreenCaptureJniBridge.nativeRequestPermission() } catch (_: Exception) {}
                },
                onContinue = { step = 1 },
            )
            1 -> PermissionStep(
                title = "Microphone Permission",
                description = "Meeting Notes also captures your microphone to label your " +
                    "speech separately from the other participant.\n\n" +
                    "macOS will prompt you on the first recording attempt.",
                actionLabel = null,
                onAction = {},
                onContinue = { step = 2 },
            )
            2 -> ModelStep(
                modelDownloadManager = modelDownloadManager,
                onContinue = { step = 3 },
            )
            3 -> WikiPathStep(
                settingsRepository = settingsRepository,
                scope = scope,
                onComplete = {
                    scope.launch { settingsRepository.markOnboardingComplete() }
                    onComplete()
                },
                onSkip = {
                    scope.launch { settingsRepository.markOnboardingComplete() }
                    onNavigate(AppDestination.Settings)
                },
            )
        }
    }
}

// ── Step composables ────────────────────────────────────────────────────────

@Composable
private fun PermissionStep(
    title: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onContinue: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actionLabel != null) {
                    OutlinedButton(onClick = onAction) { Text(actionLabel) }
                }
                Button(onClick = onContinue) { Text("Continue →") }
            }
        }
    }
}

@Composable
private fun ModelStep(
    modelDownloadManager: ModelDownloadManager,
    onContinue: () -> Unit,
) {
    val states by modelDownloadManager.states.collectAsState()
    val anyDone = states.values.any { it is ModelDownloadState.Done }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Whisper Model", style = MaterialTheme.typography.titleLarge)
            Text(
                "Download a Whisper model for on-device transcription. Models are ranked by " +
                "accuracy from the Open ASR Leaderboard. SHA-256 is verified after download.",
                style = MaterialTheme.typography.bodyMedium,
            )

            WHISPER_MODELS.forEach { spec ->
                val state = states[spec.filename] ?: ModelDownloadState.Idle
                ModelRow(
                    spec = spec,
                    state = state,
                    onDownload = { modelDownloadManager.download(spec) },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!anyDone) {
                    TextButton(onClick = onContinue) { Text("Set up later") }
                } else {
                    Button(onClick = onContinue) { Text("Continue →") }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: WhisperModelSpec,
    state: ModelDownloadState,
    onDownload: () -> Unit,
) {
    val isActive = state is ModelDownloadState.Downloading || state is ModelDownloadState.Verifying

    Surface(
        tonalElevation = if (spec.recommended) 4.dp else 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    spec.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (spec.recommended) {
                    Badge { Text("Recommended") }
                }
            }

            Text(
                spec.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                is ModelDownloadState.Idle ->
                    OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Download")
                    }

                is ModelDownloadState.Downloading -> {
                    val progress = if (state.totalBytes > 0)
                        state.bytesReceived.toFloat() / state.totalBytes else 0f
                    val receivedMb = state.bytesReceived / (1024 * 1024)
                    val totalMb = state.totalBytes / (1024 * 1024)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${(progress * 100).toInt()}%  ($receivedMb / $totalMb MB)",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                is ModelDownloadState.Verifying ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Verifying SHA-256…", style = MaterialTheme.typography.labelSmall)
                    }

                is ModelDownloadState.Done ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("✓", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4CAF50))
                        Text(
                            "Ready — ${File(state.path).name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                        )
                    }

                is ModelDownloadState.Error ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isActive,
                        ) { Text("Retry") }
                    }
            }
        }
    }
}

@Composable
private fun WikiPathStep(
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
) {
    var wikiPath by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Logseq Wiki Path", style = MaterialTheme.typography.titleLarge)
            Text(
                "Where is your Logseq wiki? Meeting notes will be exported there automatically.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = wikiPath,
                onValueChange = { wikiPath = it },
                label = { Text("Wiki root path") },
                placeholder = { Text("~/Documents/personal-wiki") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSkip) { Text("Set up later") }
                Button(
                    onClick = {
                        scope.launch {
                            if (wikiPath.isNotBlank()) {
                                val current = settingsRepository.load()
                                settingsRepository.save(current.copy(logseqWikiPath = wikiPath))
                            }
                            onComplete()
                        }
                    }
                ) { Text("Finish Setup") }
            }
        }
    }
}
