package com.meetingnotes.ui.recording

import com.meetingnotes.audio.RecordingSessionManager
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.FileStorageService
import com.meetingnotes.domain.PipelineQueueExecutor
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.RecordingState
import com.meetingnotes.domain.model.RecordingState.Transcribing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingViewModel(
    private val repository: MeetingRepository,
    settingsRepository: SettingsRepository,
    storage: FileStorageService,
    private val scope: CoroutineScope,
    private val executor: PipelineQueueExecutor,
) {
    data class HomeState(
        val recentMeetings: List<Meeting> = emptyList(),
        val processingIds: Set<String> = emptySet(),
        val processingProgress: Map<String, Transcribing> = emptyMap(),
        val justCompletedMeeting: Meeting? = null,
    )

    private val recordingManager = RecordingSessionManager(repository, storage)
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _homeState = MutableStateFlow(HomeState())
    val homeState: StateFlow<HomeState> = _homeState.asStateFlow()

    val durationMs: StateFlow<Long> = recordingManager.durationMs
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    init {
        // Load recent meetings on startup
        scope.launch(Dispatchers.IO) {
            val recent = repository.getRecentMeetings(limit = 5)
            _homeState.value = _homeState.value.copy(recentMeetings = recent)
        }

        // Observe per-job transcription progress for the home screen
        scope.launch {
            executor.jobProgress.collect { progress ->
                _homeState.value = _homeState.value.copy(processingProgress = progress)
            }
        }

        // Observe background queue: reload recent meetings and detect completions
        scope.launch {
            executor.processingIds.collect { ids ->
                val prevIds = _homeState.value.processingIds
                val justFinished = prevIds - ids

                val recent = withContext(Dispatchers.IO) { repository.getRecentMeetings(limit = 5) }

                val completedMeeting = if (justFinished.isNotEmpty()) {
                    withContext(Dispatchers.IO) { repository.getMeetingById(justFinished.first()) }
                } else null

                _homeState.value = _homeState.value.copy(
                    recentMeetings = recent,
                    processingIds = ids,
                    justCompletedMeeting = completedMeeting ?: _homeState.value.justCompletedMeeting,
                )
            }
        }
    }

    fun startRecording() {
        if (_state.value !is RecordingState.Idle) return
        val startedAt = System.currentTimeMillis()
        _state.value = RecordingState.Recording(startedAt)
        recordingManager.startRecording(scope)
        // Transcribe each 3-minute chunk as it becomes available during recording
        scope.launch {
            recordingManager.liveChunks.collect { chunk ->
                executor.transcribeLiveChunk(chunk)
            }
        }
    }

    fun stopRecording() {
        if (_state.value !is RecordingState.Recording) return
        _state.value = RecordingState.Stopping
        scope.launch {
            val (meeting, lastProcessedMs) = recordingManager.stopRecording()
            if (meeting == null) {
                _state.value = RecordingState.Error("Recording", "Failed to save recording")
                return@launch
            }
            executor.enqueue(meeting.id, lastProcessedMs)
            _state.value = RecordingState.Idle
        }
    }

    fun resetToIdle() {
        _state.value = RecordingState.Idle
    }

    fun dismissCompletion() {
        _homeState.value = _homeState.value.copy(justCompletedMeeting = null)
    }
}
