@file:OptIn(ExperimentalMaterial3Api::class)

package com.meetingnotes.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meetingnotes.data.FileStorageService
import com.meetingnotes.domain.model.LlmProvider
import com.meetingnotes.transcription.ModelDownloadManager
import com.meetingnotes.transcription.ModelDownloadState
import com.meetingnotes.transcription.WHISPER_MODELS
import com.meetingnotes.transcription.WhisperModelSpec
import com.meetingnotes.ui.AppDestination
import java.io.File

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    storage: FileStorageService,
    modelDownloadManager: ModelDownloadManager,
    onNavigate: (AppDestination) -> Unit,
) {
    val uiState by viewModel.state.collectAsState()
    val settings = uiState.settings
    val errors = uiState.validationErrors
    val scroll = rememberScrollState()

    val modelsDir = remember { File(storage.getModelsDir()) }
    val downloadStates by modelDownloadManager.states.collectAsState()

    if (uiState.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onNavigate(AppDestination.Recording) }) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        // ── Whisper Model ──────────────────────────────────────────────────
        SectionHeader("Transcription (Whisper)")

        ModelPickerSection(
            modelsDir = modelsDir,
            selectedPath = settings.whisperModelPath,
            downloadStates = downloadStates,
            onSelect = { viewModel.onSettingsChange(settings.copy(whisperModelPath = it)) },
            onDownload = { modelDownloadManager.download(it) },
        )

        if ("whisperModelPath" in errors) {
            Text(
                errors["whisperModelPath"]!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ── LLM Provider ───────────────────────────────────────────────────
        SectionHeader("AI Summarization")

        LabeledDropdown(
            label = "Provider",
            options = LlmProvider.entries,
            selected = settings.llmProvider,
            optionLabel = { it.displayName },
            onSelect = { viewModel.onSettingsChange(settings.copy(llmProvider = it)) },
        )

        OutlinedTextField(
            value = settings.llmModel,
            onValueChange = { viewModel.onSettingsChange(settings.copy(llmModel = it)) },
            label = { Text("Model name") },
            placeholder = { Text("llama3.2 / gpt-4o / claude-sonnet-4-6") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (settings.llmProvider != LlmProvider.OLLAMA) {
            OutlinedTextField(
                value = settings.llmApiKey ?: "",
                onValueChange = { viewModel.onSettingsChange(settings.copy(llmApiKey = it.ifBlank { null })) },
                label = { Text("API Key") },
                isError = "llmApiKey" in errors,
                supportingText = { errors["llmApiKey"]?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        if (settings.llmProvider == LlmProvider.OLLAMA) {
            OutlinedTextField(
                value = settings.llmBaseUrl,
                onValueChange = { viewModel.onSettingsChange(settings.copy(llmBaseUrl = it)) },
                label = { Text("Ollama base URL") },
                placeholder = { Text("http://localhost:11434") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        // ── Speaker Identification ─────────────────────────────────────────
        SectionHeader("Speaker Identification")

        AutoRecordToggle(
            label = "Enable speaker diarization",
            description = "Identifies individual speakers after recording ends. Requires Python 3.10+ with pyannote.audio installed. One-time model download requires a Hugging Face token.",
            checked = settings.diarizationEnabled,
            onCheckedChange = { viewModel.onSettingsChange(settings.copy(diarizationEnabled = it)) },
        )

        if (settings.diarizationEnabled) {
            OutlinedTextField(
                value = settings.huggingFaceToken,
                onValueChange = { viewModel.onSettingsChange(settings.copy(huggingFaceToken = it)) },
                label = { Text("Hugging Face token") },
                placeholder = { Text("hf_…") },
                isError = "huggingFaceToken" in errors,
                supportingText = {
                    errors["huggingFaceToken"]?.let { Text(it) } ?: Text(
                        "Only needed once to download the pyannote model. Can be cleared after first use. Get yours at hf.co/settings/tokens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            val speakerOptions = listOf(0, 2, 3, 4, 5, 6, 8)
            val speakerLabel = if (settings.diarizationMaxSpeakers == 0) "Auto-detect" else "${settings.diarizationMaxSpeakers} speakers"
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Max speakers:", style = MaterialTheme.typography.bodyMedium)
                    Text(speakerLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    speakerOptions.forEach { option ->
                        FilterChip(
                            selected = settings.diarizationMaxSpeakers == option,
                            onClick = { viewModel.onSettingsChange(settings.copy(diarizationMaxSpeakers = option)) },
                            label = { Text(if (option == 0) "Auto" else "$option") },
                        )
                    }
                }
            }
        }

        // ── Transcript Correction ──────────────────────────────────────────
        SectionHeader("Transcript Correction")

        AutoRecordToggle(
            label = "Enable LLM transcript correction",
            description = "Uses a local LLM (via Ollama) to fix ASR errors — misheard words, phonetic substitutions, garbled technical terms. Uses the Ollama base URL configured above.",
            checked = settings.correctionEnabled,
            onCheckedChange = { viewModel.onSettingsChange(settings.copy(correctionEnabled = it)) },
        )

        // ── Logseq ─────────────────────────────────────────────────────────
        SectionHeader("Logseq Export")

        OutlinedTextField(
            value = settings.logseqWikiPath,
            onValueChange = { viewModel.onSettingsChange(settings.copy(logseqWikiPath = it)) },
            label = { Text("Wiki root path") },
            placeholder = { Text("~/Documents/personal-wiki") },
            isError = "logseqWikiPath" in errors,
            supportingText = { errors["logseqWikiPath"]?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // ── Recordings ─────────────────────────────────────────────────────
        // ── Automation ─────────────────────────────────────────────────────────
        SectionHeader("Automation")

        AutoRecordToggle(
            label = "Auto-record Zoom meetings",
            description = "Start recording automatically when a Zoom meeting begins and stop when it ends.",
            checked = settings.autoRecordZoom,
            onCheckedChange = { viewModel.onSettingsChange(settings.copy(autoRecordZoom = it)) },
        )

        AutoRecordToggle(
            label = "Auto-record Google Meet meetings",
            description = "Start recording automatically when a Google Meet tab is open. Requires Automation permission for your browser.",
            checked = settings.autoRecordGoogleMeet,
            onCheckedChange = { viewModel.onSettingsChange(settings.copy(autoRecordGoogleMeet = it)) },
        )

        SectionHeader("Recording Retention")

        RetentionPicker(
            days = settings.recordingRetentionDays,
            onChange = { viewModel.onSettingsChange(settings.copy(recordingRetentionDays = it)) },
        )

        // ── Save ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uiState.saveSuccess) {
                Text("Saved!", color = MaterialTheme.colorScheme.primary)
            }
            Button(onClick = viewModel::save) { Text("Save Settings") }
        }
    }
}

// ── Model picker ─────────────────────────────────────────────────────────────

@Composable
private fun ModelPickerSection(
    modelsDir: File,
    selectedPath: String,
    downloadStates: Map<String, ModelDownloadState>,
    onSelect: (String) -> Unit,
    onDownload: (WhisperModelSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WHISPER_MODELS.forEach { spec ->
            val file = File(modelsDir, spec.filename)
            val state = downloadStates[spec.filename] ?: ModelDownloadState.Idle
            val isSelected = selectedPath == file.absolutePath

            ModelRow(
                spec = spec,
                state = state,
                isSelected = isSelected,
                onSelect = if (state is ModelDownloadState.Done) ({ onSelect(file.absolutePath) }) else null,
                onDownload = { onDownload(spec) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    spec: WhisperModelSpec,
    state: ModelDownloadState,
    isSelected: Boolean,
    onSelect: (() -> Unit)?,
    onDownload: () -> Unit,
) {
    val isDownloaded = state is ModelDownloadState.Done
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isDownloaded -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    Surface(
        onClick = { onSelect?.invoke() },
        enabled = onSelect != null,
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = if (isSelected) 3.dp else 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = when {
                        isSelected -> "●"
                        isDownloaded -> "○"
                        else -> "·"
                    },
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isDownloaded -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            spec.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDownloaded) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                        if (spec.recommended) Badge { Text("Recommended") }
                    }
                    Text(
                        spec.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isDownloaded) 1f else 0.4f,
                        ),
                    )
                }

                when {
                    isSelected -> Text(
                        "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    isDownloaded -> Text(
                        "Downloaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Download progress or button — shown only while not yet downloaded
            when (state) {
                is ModelDownloadState.Idle ->
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download") }

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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        ) { Text("Retry") }
                    }

                is ModelDownloadState.Done -> { /* nothing extra — already shown as Downloaded/Active */ }
            }
        }
    }
}

// ── Retention picker ─────────────────────────────────────────────────────────

@Composable
private fun RetentionPicker(days: Int, onChange: (Int) -> Unit) {
    val options = listOf(7, 14, 30, 60, 90, 0)
    val label = if (days == 0) "Keep forever" else "$days days"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Keep recordings for:", style = MaterialTheme.typography.bodyMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Audio recordings are deleted after this period. Transcripts and summaries are kept permanently.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = days == option,
                    onClick = { onChange(option) },
                    label = { Text(if (option == 0) "∞" else "${option}d") },
                )
            }
        }
    }
}

// ── Auto-record toggle ────────────────────────────────────────────────────────

@Composable
private fun AutoRecordToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider()
}

@Composable
private fun <T> LabeledDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
