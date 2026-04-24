package com.meetingnotes.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.domain.model.TranscriptionMetrics
import com.meetingnotes.ui.AppDestination
import com.meetingnotes.ui.components.ErrorBanner

@Composable
fun TranscriptScreen(
    viewModel: TranscriptViewModel,
    onNavigate: (AppDestination) -> Unit,
) {
    val uiState by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Release audio player when navigating away
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPlayer() }
    }

    // Navigate to History after deletion
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigate(AppDestination.History)
    }

    // Auto-scroll so the active segment stays visible
    LaunchedEffect(uiState.activeSegmentId) {
        val idx = uiState.segments.indexOfFirst { it.id == uiState.activeSegmentId }
        if (idx >= 0) {
            // Header items before first segment:
            //   0 = processing time (optional), 1 = summary/summarize, 2 = "Full Transcript" label
            val headerCount = if (uiState.meeting?.transcriptionDurationMs != null) 3 else 2
            listState.animateScrollToItem((idx + headerCount).coerceAtLeast(0))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete meeting?") },
            text = { Text("This will permanently delete the recording, transcript, and summary.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onNavigate(AppDestination.History) }) { Text("← History") }
            Spacer(Modifier.width(8.dp))
            if (uiState.isEditingTitle) {
                OutlinedTextField(
                    value = uiState.titleDraft,
                    onValueChange = viewModel::onTitleDraftChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    trailingIcon = {
                        Row {
                            IconButton(onClick = viewModel::confirmRename) { Text("✓") }
                            IconButton(onClick = viewModel::cancelRename) { Text("✕") }
                        }
                    },
                )
            } else {
                Text(
                    uiState.meeting?.title ?: "Transcript",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.meeting != null) {
                    IconButton(onClick = viewModel::startEditTitle) { Text("✎") }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
            }
        }
        HorizontalDivider()

        // ── Audio player bar ────────────────────────────────────────────────────
        if (uiState.playerDurationMs > 0) {
            PlayerBar(
                positionMs = uiState.playerPositionMs,
                durationMs = uiState.playerDurationMs,
                isPlaying = uiState.playerIsPlaying,
                onPlayPause = viewModel::playPause,
                onSeek = viewModel::seekTo,
            )
            HorizontalDivider()
        }

        when {
            uiState.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.meeting == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Meeting not found")
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Processing time — expandable to show per-stage breakdown
                    uiState.meeting?.let { meeting ->
                        val txMs = meeting.transcriptionDurationMs
                        val audMs = meeting.audioDurationMs
                        if (txMs != null) {
                            item {
                                TranscriptionTimingRow(
                                    transcriptionDurationMs = txMs,
                                    audioDurationMs = audMs,
                                    metrics = uiState.transcriptionMetrics,
                                )
                            }
                        }
                    }

                    // Summary section or "Generate Summary" button
                    val summary = uiState.summary
                    if (summary != null) {
                        item { SummarySection(summary) }
                    } else {
                        item {
                            SummarizeSection(
                                status = uiState.summarizeStatus,
                                onSummarize = viewModel::summarize,
                                onDismissError = viewModel::dismissSummarizeError,
                            )
                        }
                    }

                    // Transcript header / retranscribe section
                    item {
                        if (uiState.segments.isEmpty()) {
                            RetranscribeSection(
                                status = uiState.retranscribeStatus,
                                onRetranscribe = viewModel::retranscribe,
                            )
                        } else {
                            Text(
                                "Full Transcript",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    items(uiState.segments, key = { it.id }) { segment ->
                        SegmentRow(
                            segment = segment,
                            isActive = segment.id == uiState.activeSegmentId,
                        )
                    }

                    // Action buttons
                    item {
                        Spacer(Modifier.height(8.dp))
                        ActionButtons(
                            meeting = uiState.meeting,
                            exportStatus = uiState.exportStatus,
                            retranscribeStatus = uiState.retranscribeStatus,
                            hasSegments = uiState.segments.isNotEmpty(),
                            onExport = viewModel::exportToLogseq,
                            onCopy = {
                                clipboard.setText(AnnotatedString(viewModel.copyTranscript()))
                            },
                            onDismissExport = viewModel::dismissExportStatus,
                            onRetranscribe = viewModel::retranscribe,
                        )
                    }
                }
            }
        }
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
private fun PlayerBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onPlayPause) {
            Text(if (isPlaying) "⏸" else "▶", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            formatTimestamp(positionMs),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = if (durationMs > 0L) positionMs.toFloat() / durationMs.toFloat() else 0f,
            onValueChange = { fraction -> onSeek((fraction * durationMs).toLong()) },
            modifier = Modifier.weight(1f),
        )
        Text(
            formatTimestamp(durationMs),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun TranscriptionTimingRow(
    transcriptionDurationMs: Long,
    audioDurationMs: Long?,
    metrics: TranscriptionMetrics?,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        val speedLabel = if (audioDurationMs != null && transcriptionDurationMs > 0) {
            val ratio = audioDurationMs.toDouble() / transcriptionDurationMs
            "Transcribed in ${transcriptionDurationMs / 1000}s (%.1f×realtime)".format(ratio)
        } else {
            "Transcribed in ${transcriptionDurationMs / 1000}s"
        }
        val indicator = if (metrics != null) if (expanded) " ▲" else " ▼" else ""
        Text(
            speedLabel + indicator,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (metrics != null) Modifier.clickable { expanded = !expanded } else Modifier,
        )
        if (expanded && metrics != null) {
            val stages = listOf(
                "Preprocess" to metrics.preprocessMs,
                "Model load" to metrics.modelLoadMs,
                "Mic infer" to metrics.micInferenceMs,
                "Sys infer" to metrics.sysInferenceMs,
                "Persist" to metrics.persistMs,
            )
            val totalMs = stages.sumOf { it.second }.coerceAtLeast(1L)
            val maxMs = stages.maxOf { it.second }
            Column(
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                stages.forEach { (label, ms) ->
                    MetricRow(label, ms, totalMs, isBottleneck = ms == maxMs && ms > 0)
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, ms: Long, totalMs: Long, isBottleneck: Boolean) {
    val fraction = if (totalMs > 0) (ms.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val color = if (isBottleneck) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val textColor = if (isBottleneck) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.width(80.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(color),
            )
        }
        Text(
            formatMs(ms),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End,
        )
    }
}

private fun formatMs(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    else -> "${ms / 1000}s"
}

@Composable
private fun SummarySection(summary: com.meetingnotes.domain.model.MeetingSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleMedium)

            if (summary.keyPoints.isNotEmpty()) {
                Text("Key Points", style = MaterialTheme.typography.titleSmall)
                summary.keyPoints.forEach { Text("• $it") }
            }
            if (summary.decisions.isNotEmpty()) {
                Text("Decisions", style = MaterialTheme.typography.titleSmall)
                summary.decisions.forEach { Text("• $it") }
            }
            if (summary.actionItems.isNotEmpty()) {
                Text("Action Items", style = MaterialTheme.typography.titleSmall)
                summary.actionItems.forEach { item ->
                    val owner = item.owner?.let { "[$it] " } ?: ""
                    val due = item.dueDate?.let { " - due $it" } ?: ""
                    Text("☐ $owner${item.text}$due")
                }
            }
        }
    }
}

@Composable
private fun SummarizeSection(
    status: TranscriptViewModel.SummarizeStatus,
    onSummarize: () -> Unit,
    onDismissError: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No summary yet", style = MaterialTheme.typography.titleMedium)
            when (val s = status) {
                is TranscriptViewModel.SummarizeStatus.Failed -> {
                    ErrorBanner(
                        message = s.message,
                        retryable = true,
                        onRetry = onSummarize,
                        onDismiss = onDismissError,
                    )
                }
                else -> Unit
            }
            Button(
                onClick = onSummarize,
                enabled = status !is TranscriptViewModel.SummarizeStatus.Running,
            ) {
                if (status is TranscriptViewModel.SummarizeStatus.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Generate Summary with AI")
            }
        }
    }
}

@Composable
private fun RetranscribeSection(
    status: TranscriptViewModel.RetranscribeStatus,
    onRetranscribe: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                is TranscriptViewModel.RetranscribeStatus.Queued -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Transcribing in background...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is TranscriptViewModel.RetranscribeStatus.NoAudioFile -> {
                    Text("No transcript", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The audio file for this meeting has been deleted and cannot be re-transcribed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text("No transcript yet", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onRetranscribe) { Text("Transcribe") }
                }
            }
        }
    }
}

@Composable
private fun SegmentRow(segment: TranscriptSegment, isActive: Boolean) {
    val speakerColor = when (segment.speakerLabel) {
        "You" -> MaterialTheme.colorScheme.primary
        "Caller" -> Color(0xFF2E7D32) // green
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                formatTimestamp(segment.startMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )
            Column {
                Text(
                    segment.speakerLabel ?: "?",
                    style = MaterialTheme.typography.labelSmall,
                    color = speakerColor,
                )
                Text(segment.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ActionButtons(
    meeting: Meeting?,
    exportStatus: TranscriptViewModel.ExportStatus,
    retranscribeStatus: TranscriptViewModel.RetranscribeStatus,
    hasSegments: Boolean,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onDismissExport: () -> Unit,
    onRetranscribe: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = exportStatus) {
            is TranscriptViewModel.ExportStatus.Failed -> {
                ErrorBanner(
                    message = s.message,
                    retryable = true,
                    onRetry = onExport,
                    onDismiss = onDismissExport,
                )
            }
            is TranscriptViewModel.ExportStatus.Success -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(
                        "Exported to Logseq: ${s.pagePath}",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onExport,
                enabled = exportStatus !is TranscriptViewModel.ExportStatus.Exporting,
            ) {
                if (exportStatus is TranscriptViewModel.ExportStatus.Exporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Export to Logseq")
            }
            OutlinedButton(onClick = onCopy) { Text("Copy Transcript") }
            if (hasSegments && meeting?.audioFilePath?.isNotBlank() == true) {
                OutlinedButton(
                    onClick = onRetranscribe,
                    enabled = retranscribeStatus !is TranscriptViewModel.RetranscribeStatus.Queued,
                ) {
                    if (retranscribeStatus is TranscriptViewModel.RetranscribeStatus.Queued) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Re-transcribe")
                }
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val total = ms / 1000
    val m = (total % 3600) / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
