package com.meetingnotes.ui.transcript

import com.meetingnotes.audio.AudioPlayer
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.llm.LlmProviderFactory
import com.meetingnotes.domain.ExportToLogseqUseCase
import com.meetingnotes.domain.PipelineQueueExecutor
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.MeetingSummary
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.domain.model.TranscriptionMetrics
import com.meetingnotes.export.LogseqExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class TranscriptViewModel(
    private val meetingId: String,
    private val repository: MeetingRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val executor: PipelineQueueExecutor? = null,
) {
    data class UiState(
        val meeting: Meeting? = null,
        val summary: MeetingSummary? = null,
        val segments: List<TranscriptSegment> = emptyList(),
        val loading: Boolean = true,
        val exportStatus: ExportStatus = ExportStatus.Idle,
        val summarizeStatus: SummarizeStatus = SummarizeStatus.Idle,
        val deleted: Boolean = false,
        val isEditingTitle: Boolean = false,
        val titleDraft: String = "",
        // ── Player ───────────────────────────────────────────────────────────
        val playerPositionMs: Long = 0L,
        val playerDurationMs: Long = 0L,
        val playerIsPlaying: Boolean = false,
        val activeSegmentId: String? = null,
        // ── Transcription metrics ─────────────────────────────────────────────
        val transcriptionMetrics: TranscriptionMetrics? = null,
        // ── Retranscribe ──────────────────────────────────────────────────────
        val retranscribeStatus: RetranscribeStatus = RetranscribeStatus.Idle,
    )

    sealed class RetranscribeStatus {
        data object Idle : RetranscribeStatus()
        data object Queued : RetranscribeStatus()
        data object NoAudioFile : RetranscribeStatus()
    }

    sealed class ExportStatus {
        data object Idle : ExportStatus()
        data object Exporting : ExportStatus()
        data class Success(val pagePath: String) : ExportStatus()
        data class Failed(val message: String) : ExportStatus()
    }

    sealed class SummarizeStatus {
        data object Idle : SummarizeStatus()
        data object Running : SummarizeStatus()
        data class Failed(val message: String) : SummarizeStatus()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var audioPlayer: AudioPlayer? = null

    init {
        scope.launch(Dispatchers.IO) {
            val meeting = repository.getMeetingById(meetingId)
            val summary = repository.getSummaryByMeetingId(meetingId)
            val segments = repository.getSegmentsByMeetingId(meetingId)
                .sortedBy { it.startMs }

            val metrics = meeting?.transcriptionMetricsJson?.let {
                runCatching { Json.decodeFromString<TranscriptionMetrics>(it) }.getOrNull()
            }

            _state.value = UiState(
                meeting = meeting,
                summary = summary,
                segments = segments,
                loading = false,
                transcriptionMetrics = metrics,
            )

            // Observe the background pipeline queue so the UI reflects in-progress transcription
            // and auto-reloads segments when processing completes. Launched after initial state is
            // set so the first StateFlow emission doesn't race with UiState initialisation.
            if (executor != null) {
                scope.launch {
                    executor.processingIds.collect { ids ->
                        val wasQueued = _state.value.retranscribeStatus is RetranscribeStatus.Queued
                        if (meetingId in ids) {
                            _state.value = _state.value.copy(retranscribeStatus = RetranscribeStatus.Queued)
                        } else if (wasQueued) {
                            val reloaded = withContext(Dispatchers.IO) {
                                repository.getSegmentsByMeetingId(meetingId).sortedBy { it.startMs }
                            }
                            val updatedMeeting = withContext(Dispatchers.IO) {
                                repository.getMeetingById(meetingId)
                            }
                            _state.value = _state.value.copy(
                                segments = reloaded,
                                meeting = updatedMeeting ?: _state.value.meeting,
                                retranscribeStatus = RetranscribeStatus.Idle,
                            )
                        }
                    }
                }
            }

            // Initialise the audio player if the audio file exists.
            if (!meeting?.audioFilePath.isNullOrBlank()) {
                runCatching {
                    val player = AudioPlayer()
                    player.load(meeting!!.audioFilePath)
                    audioPlayer = player

                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(playerDurationMs = player.durationMs)

                        scope.launch {
                            player.positionMs.collect { ms ->
                                _state.value = _state.value.copy(
                                    playerPositionMs = ms,
                                    activeSegmentId = findActiveSegment(ms, _state.value.segments),
                                )
                            }
                        }
                        scope.launch {
                            player.isPlaying.collect { playing ->
                                _state.value = _state.value.copy(playerIsPlaying = playing)
                            }
                        }
                    }
                }
            }
        }
    }

    fun exportToLogseq() {
        _state.value = _state.value.copy(exportStatus = ExportStatus.Exporting)
        scope.launch {
            val useCase = ExportToLogseqUseCase(
                exporter = LogseqExporter(),
                repository = repository,
                settingsRepository = settingsRepository,
            )
            val result = withContext(Dispatchers.IO) { useCase.execute(meetingId) }
            val status = result.fold(
                onSuccess = { (pagePath, _) -> ExportStatus.Success(pagePath) },
                onFailure = { ExportStatus.Failed(it.message ?: "Export failed") },
            )
            _state.value = _state.value.copy(exportStatus = status)
        }
    }

    fun dismissExportStatus() {
        _state.value = _state.value.copy(exportStatus = ExportStatus.Idle)
    }

    fun summarize() {
        _state.value = _state.value.copy(summarizeStatus = SummarizeStatus.Running)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val settings = settingsRepository.load()
                    val provider = LlmProviderFactory.create(settings)
                    val segments = repository.getSegmentsByMeetingId(meetingId)
                    val rawSummary = provider.summarize(segments, settings)
                    val summary = rawSummary.copy(meetingId = meetingId)
                    repository.insertSummary(summary)
                    repository.updateMeetingSummaryModel(
                        meetingId,
                        "${settings.llmProvider.name.lowercase()}/${settings.llmModel}",
                    )
                    repository.getSummaryByMeetingId(meetingId)
                }
            }
            result.fold(
                onSuccess = { summary ->
                    _state.value = _state.value.copy(
                        summary = summary,
                        summarizeStatus = SummarizeStatus.Idle,
                    )
                },
                onFailure = { cause ->
                    _state.value = _state.value.copy(
                        summarizeStatus = SummarizeStatus.Failed(cause.message ?: "Summarization failed"),
                    )
                },
            )
        }
    }

    fun dismissSummarizeError() {
        _state.value = _state.value.copy(summarizeStatus = SummarizeStatus.Idle)
    }

    fun startEditTitle() {
        val currentTitle = _state.value.meeting?.title ?: return
        _state.value = _state.value.copy(isEditingTitle = true, titleDraft = currentTitle)
    }

    fun onTitleDraftChange(draft: String) {
        _state.value = _state.value.copy(titleDraft = draft)
    }

    fun confirmRename() {
        val draft = _state.value.titleDraft.trim()
        if (draft.isBlank()) { cancelRename(); return }
        _state.value = _state.value.copy(isEditingTitle = false)
        scope.launch(Dispatchers.IO) {
            repository.updateMeetingTitle(meetingId, draft)
            val updated = repository.getMeetingById(meetingId)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(meeting = updated)
            }
        }
    }

    fun cancelRename() {
        _state.value = _state.value.copy(isEditingTitle = false, titleDraft = "")
    }

    fun retranscribe() {
        val meeting = _state.value.meeting ?: return
        if (meeting.audioFilePath.isBlank()) {
            _state.value = _state.value.copy(retranscribeStatus = RetranscribeStatus.NoAudioFile)
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { repository.deleteSegmentsByMeetingId(meetingId) }
            _state.value = _state.value.copy(segments = emptyList())
            executor?.enqueue(meetingId)
        }
    }

    fun delete() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val path = _state.value.meeting?.audioFilePath
                if (!path.isNullOrBlank()) java.io.File(path).delete()
                repository.deleteMeeting(meetingId)
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(deleted = true)
            }
        }
    }

    // ── Audio player ─────────────────────────────────────────────────────────

    fun playPause() {
        audioPlayer?.playPause()
    }

    fun seekTo(ms: Long) {
        audioPlayer?.seekTo(ms)
    }

    /** Stop and release the audio player. Call from DisposableEffect.onDispose. */
    fun stopPlayer() {
        audioPlayer?.close()
        audioPlayer = null
    }

    fun copyTranscript(): String {
        val segments = _state.value.segments
        return segments.joinToString("\n") { seg ->
            val ts = formatTimestamp(seg.startMs)
            "[${ts}] ${seg.speakerLabel ?: "Unknown"}: ${seg.text}"
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun findActiveSegment(posMs: Long, segments: List<TranscriptSegment>): String? =
        segments.firstOrNull { posMs >= it.startMs && posMs < it.endMs }?.id
            ?: segments.lastOrNull { it.startMs <= posMs }?.id

    private fun formatTimestamp(ms: Long): String {
        val total = ms / 1000
        val m = (total % 3600) / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }
}
