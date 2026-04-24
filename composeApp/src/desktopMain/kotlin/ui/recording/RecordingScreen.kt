package com.meetingnotes.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meetingnotes.audio.ActiveMeeting
import com.meetingnotes.audio.MeetingSource
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.RecordingState
import com.meetingnotes.ui.AppDestination
import com.meetingnotes.ui.components.ErrorBanner
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel,
    onNavigate: (AppDestination) -> Unit,
    activeMeeting: ActiveMeeting? = null,
    autoRecordEnabled: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val homeState by viewModel.homeState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Recording controls - vertically centered in the upper portion
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Agrapha", style = MaterialTheme.typography.headlineMedium)

                Spacer(Modifier.height(8.dp))

                when (val s = state) {
                    is RecordingState.Idle -> {
                        if (autoRecordEnabled && activeMeeting != null) {
                            MeetingDetectedBanner(activeMeeting)
                        } else if (autoRecordEnabled) {
                            Text(
                                "Auto-record enabled — waiting for a meeting…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = viewModel::startRecording,
                            enabled = !(autoRecordEnabled && activeMeeting != null),
                        ) {
                            Text("Start Recording")
                        }
                    }

                    is RecordingState.Recording -> {
                        Text(
                            formatDuration(durationMs),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text("Recording…", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = viewModel::stopRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            Text("Stop Recording")
                        }
                    }

                    is RecordingState.Stopping -> {
                        CircularProgressIndicator()
                        Text("Saving…", style = MaterialTheme.typography.bodyLarge)
                    }

                    is RecordingState.Transcribing -> {
                        val transcriptionStartMs = remember { System.currentTimeMillis() }
                        LinearProgressIndicator(
                            progress = { s.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (s.totalChunks > 0) {
                                Text(
                                    "Chunk ${s.currentChunk} of ${s.totalChunks}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val etaLabel = remember(s.currentChunk) {
                                    etaLabel(transcriptionStartMs, s.currentChunk, s.totalChunks)
                                }
                                Text(
                                    etaLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text("Transcribing… ${s.progress}%", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    is RecordingState.Diarizing -> {
                        CircularProgressIndicator()
                        Text("Identifying speakers…", style = MaterialTheme.typography.bodyLarge)
                    }

                    is RecordingState.CorrectingTranscript -> {
                        CircularProgressIndicator()
                        Text("Correcting transcript…", style = MaterialTheme.typography.bodyLarge)
                    }

                    is RecordingState.Summarizing -> {
                        CircularProgressIndicator()
                        Text("Summarizing with AI…", style = MaterialTheme.typography.bodyLarge)
                    }

                    is RecordingState.Exporting -> {
                        CircularProgressIndicator()
                        Text("Exporting to Logseq…", style = MaterialTheme.typography.bodyLarge)
                    }

                    is RecordingState.Complete -> {
                        Text("✓", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                        Text("Done!", style = MaterialTheme.typography.headlineSmall)
                        if (s.summarySkipped) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ) {
                                Text(
                                    "AI summary skipped — Ollama was not available. " +
                                        "You can generate a summary from the Transcript screen.",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(onClick = { onNavigate(AppDestination.Transcript(s.meeting.id)) }) {
                            Text("View Transcript")
                        }
                        OutlinedButton(onClick = viewModel::resetToIdle) {
                            Text("New Recording")
                        }
                    }

                    is RecordingState.Error -> {
                        ErrorBanner(
                            message = "${s.stage} failed: ${s.message}",
                            retryable = false,
                            onRetry = viewModel::resetToIdle,
                            onDismiss = viewModel::resetToIdle,
                        )
                    }
                }
            }
        }

        // Recent meetings + processing status — visible when idle
        if (state is RecordingState.Idle) {
            HorizontalDivider()
            RecentMeetingsSection(
                homeState = homeState,
                onNavigate = onNavigate,
                onDismissCompleted = viewModel::dismissCompletion,
            )
        }

        // Bottom navigation
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = { onNavigate(AppDestination.History) }) { Text("History") }
            TextButton(onClick = { onNavigate(AppDestination.Settings) }) { Text("Settings") }
        }
    }
}

@Composable
private fun RecentMeetingsSection(
    homeState: RecordingViewModel.HomeState,
    onNavigate: (AppDestination) -> Unit,
    onDismissCompleted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Completion notification
        homeState.justCompletedMeeting?.let { meeting ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "✓ Ready: ${meeting.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        TextButton(onClick = {
                            onDismissCompleted()
                            onNavigate(AppDestination.Transcript(meeting.id))
                        }) { Text("View") }
                        TextButton(onClick = onDismissCompleted) { Text("✕") }
                    }
                }
            }
        }

        // In-progress processing items
        homeState.processingIds.forEach { id ->
            val meeting = homeState.recentMeetings.find { it.id == id }
            val progress = homeState.processingProgress[id]
            ProcessingItem(title = meeting?.title, progress = progress)
        }

        // Recent completed meetings
        val displayMeetings = homeState.recentMeetings.filter { it.id !in homeState.processingIds }
        if (displayMeetings.isNotEmpty()) {
            if (homeState.processingIds.isNotEmpty() || homeState.justCompletedMeeting != null) {
                HorizontalDivider()
            }
            Text(
                "Recent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            displayMeetings.forEach { meeting ->
                MeetingRow(meeting = meeting, onClick = { onNavigate(AppDestination.Transcript(meeting.id)) })
            }
        }
    }
}

@Composable
private fun MeetingRow(meeting: Meeting, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                meeting.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatMeetingDate(meeting.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProcessingItem(title: String?, progress: RecordingState.Transcribing?) {
    val startMs = remember { System.currentTimeMillis() }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                title ?: "Processing…",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null && progress.totalChunks > 0) {
                Text(
                    "Chunk ${progress.currentChunk}/${progress.totalChunks}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (progress != null && progress.totalChunks > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { progress.progress / 100f },
                    modifier = Modifier.weight(1f).height(3.dp),
                )
                Spacer(Modifier.width(8.dp))
                val eta = remember(progress.currentChunk) {
                    etaLabel(startMs, progress.currentChunk, progress.totalChunks)
                }
                Text(eta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MeetingDetectedBanner(meeting: ActiveMeeting) {
    val durationMs by produceState(initialValue = meeting.durationMs) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            value = meeting.durationMs
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Column {
                val label = when (meeting.source) {
                    MeetingSource.GOOGLE_MEET ->
                        if (meeting.title != null) "Google Meet: ${meeting.title}" else "Google Meet"
                    MeetingSource.ZOOM -> "Zoom meeting"
                }
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text(
                    formatDuration(durationMs) + " — recording starting…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun etaLabel(startMs: Long, currentChunk: Int, totalChunks: Int): String {
    if (currentChunk <= 0 || totalChunks <= 0) return ""
    val elapsed = System.currentTimeMillis() - startMs
    val avgPerChunk = elapsed / currentChunk
    val remaining = (totalChunks - currentChunk) * avgPerChunk
    return when {
        remaining >= 60_000 -> "~${(remaining / 60_000.0).roundToLong()}m left"
        remaining >= 5_000 -> "~${remaining / 1000}s left"
        else -> "almost done"
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatMeetingDate(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val then = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
    val today = LocalDate.now(zone)
    val timeStr = then.format(DateTimeFormatter.ofPattern("h:mm a"))
    return when (then.toLocalDate()) {
        today -> "Today at $timeStr"
        today.minusDays(1) -> "Yesterday at $timeStr"
        else -> then.format(DateTimeFormatter.ofPattern("MMM d")) + " at $timeStr"
    }
}
