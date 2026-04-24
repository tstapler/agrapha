package com.meetingnotes.ui.history

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.domain.model.Meeting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: MeetingRepository,
    private val scope: CoroutineScope,
) {
    data class UiState(
        val meetings: List<Meeting> = emptyList(),
        val query: String = "",
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var allMeetings: List<Meeting> = emptyList()

    init {
        load()
    }

    /** Reload from the database (e.g. after returning from TranscriptScreen). */
    fun refresh() = load()

    fun search(q: String) {
        _state.value = _state.value.copy(
            query = q,
            meetings = if (q.isBlank()) allMeetings
                       else allMeetings.filter { it.title.contains(q, ignoreCase = true) },
        )
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun load() {
        scope.launch(Dispatchers.IO) {
            allMeetings = repository.getAllMeetings().sortedByDescending { it.startedAt }
            _state.value = UiState(meetings = allMeetings, loading = false)
        }
    }
}
