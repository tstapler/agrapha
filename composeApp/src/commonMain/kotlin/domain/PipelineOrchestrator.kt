package com.meetingnotes.domain

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.domain.llm.LlmProvider
import com.meetingnotes.domain.llm.LlmUnavailableException
import com.meetingnotes.domain.model.Meeting
import com.meetingnotes.domain.model.RecordingState
import com.meetingnotes.domain.model.TranscriptSegment
import com.meetingnotes.domain.model.TranscriptionMetrics
import com.meetingnotes.export.LogseqExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Runs the post-recording pipeline:
 *   Stage 0: Transcription (fatal on failure)
 *   Stage 1: Diarization (non-fatal, optional — only runs when [diarizationRunner] is provided)
 *   Stage 2: Transcript Correction (non-fatal, optional — only runs when [correctionRunner] is provided)
 *   Stage 3: Summarization (fatal on failure)
 *   Stage 4: Logseq Export (fatal on failure)
 *
 * State is communicated via [stateFlow]. On failure at a fatal stage, the orchestrator:
 * - Emits [RecordingState.Error] with [retryable=true]
 * - Preserves all data written in prior stages (no rollback)
 * - Can be resumed with [retry] which skips completed stages
 *
 * @param diarizationRunner Optional suspend lambda: receives (audioFilePath, meetingId), returns
 *   updated segments with numbered speaker labels or null to skip/fail silently. Responsible for
 *   audio channel splitting and pyannote invocation.
 * @param correctionRunner Optional suspend lambda: receives current segments, returns corrected
 *   segments. Responsible for LLM invocation and fail-safe handling.
 *
 * Usage:
 * ```kotlin
 * orchestrator.run(meeting, modelPath)  // starts from stage 0
 * // On Error state:
 * orchestrator.retry(modelPath)         // resumes from failed stage
 * ```
 */
class PipelineOrchestrator(
    private val transcriptionUseCase: TranscriptionUseCase,
    private val llmProvider: LlmProvider,
    private val exporter: LogseqExporter,
    private val repository: MeetingRepository,
    private val settingsRepository: SettingsRepository,
    private val stateFlow: MutableStateFlow<RecordingState>,
    private val diarizationRunner: (suspend (audioPath: String, meetingId: String) -> List<TranscriptSegment>?)? = null,
    private val correctionRunner: (suspend (segments: List<TranscriptSegment>) -> List<TranscriptSegment>)? = null,
) {
    private var lastFailedStage: Int = -1
    private var currentMeeting: Meeting? = null

    /** Run the full pipeline for [meeting] starting at stage 0. */
    suspend fun run(meeting: Meeting, modelPath: String) {
        currentMeeting = meeting
        lastFailedStage = -1
        executeFrom(meeting, modelPath, startStage = 0)
    }

    /** Resume the pipeline from the stage that previously failed. */
    suspend fun retry(modelPath: String) {
        val meeting = currentMeeting ?: return
        executeFrom(meeting, modelPath, startStage = lastFailedStage)
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private suspend fun executeFrom(meeting: Meeting, modelPath: String, startStage: Int) {
        println("[PipelineOrchestrator] executeFrom: meetingId=${meeting.id} startStage=$startStage model=${modelPath.substringAfterLast('/')}")
        // ── Stage 0: Transcription ─────────────────────────────────────────
        if (startStage <= 0) {
            println("[PipelineOrchestrator] Stage 0: Transcription starting")
            stateFlow.value = RecordingState.Transcribing(0)
            var transcriptionDurationMs: Long? = null
            var capturedMetrics: TranscriptionMetrics? = null
            try {
                val startMs = System.currentTimeMillis()
                transcriptionUseCase.transcribe(
                    audioFilePath = meeting.audioFilePath,
                    meetingId = meeting.id,
                    modelPath = modelPath,
                ).collect { progress ->
                    stateFlow.value = RecordingState.Transcribing(
                        progress = progress.percent,
                        stage = progress.stage,
                        currentChunk = progress.currentChunk,
                        totalChunks = progress.totalChunks,
                    )
                    if (progress.metrics != null) capturedMetrics = progress.metrics
                }
                transcriptionDurationMs = System.currentTimeMillis() - startMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailedStage = 0
                println("[PipelineOrchestrator] Stage 0 FAILED: ${e.message}")
                stateFlow.value = RecordingState.Error(
                    stage = "Transcription",
                    message = e.message ?: "Transcription failed",
                    retryable = true,
                )
                return
            }
            println("[PipelineOrchestrator] Stage 0 complete: transcription took ${transcriptionDurationMs}ms")
            // Persist timing and stage metrics — non-fatal.
            try {
                withContext(Dispatchers.IO) {
                    val audioDurationMs = meeting.durationSeconds?.let { it * 1000L }
                    repository.updateMeetingTimings(meeting.id, audioDurationMs, transcriptionDurationMs)
                    capturedMetrics?.let { repository.updateMeetingMetrics(meeting.id, Json.encodeToString(it)) }
                }
            } catch (_: Exception) {
                // Timing metadata is best-effort — continue to diarization
            }
        }

        // ── Stage 1: Diarization (non-fatal) ──────────────────────────────
        if (startStage <= 1 && diarizationRunner != null) {
            println("[PipelineOrchestrator] Stage 1: Diarization starting")
            stateFlow.value = RecordingState.Diarizing
            try {
                val updatedSegments = diarizationRunner(meeting.audioFilePath, meeting.id)
                if (updatedSegments != null) {
                    withContext(Dispatchers.IO) {
                        repository.deleteSegmentsByMeetingId(meeting.id)
                        updatedSegments.forEach { repository.insertSegment(it) }
                    }
                    println("[PipelineOrchestrator] Stage 1 complete: diarization applied ${updatedSegments.size} segments")
                } else {
                    println("[PipelineOrchestrator] Stage 1 skipped: diarization runner returned null")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-fatal — original "Caller" labels are preserved
                println("[PipelineOrchestrator] Stage 1 FAILED (non-fatal): ${e.message}")
            }
        }

        // ── Stage 2: Transcript Correction (non-fatal) ────────────────────
        if (startStage <= 2 && correctionRunner != null) {
            println("[PipelineOrchestrator] Stage 2: Transcript Correction starting")
            stateFlow.value = RecordingState.CorrectingTranscript
            try {
                val segments = withContext(Dispatchers.IO) { repository.getSegmentsByMeetingId(meeting.id) }
                val corrected = correctionRunner(segments)
                withContext(Dispatchers.IO) {
                    repository.deleteSegmentsByMeetingId(meeting.id)
                    corrected.forEach { repository.insertSegment(it) }
                }
                println("[PipelineOrchestrator] Stage 2 complete: correction applied ${corrected.size} segments")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-fatal — original transcribed text is preserved
                println("[PipelineOrchestrator] Stage 2 FAILED (non-fatal): ${e.message}")
            }
        }

        // ── Stage 3: Summarization ─────────────────────────────────────────
        if (startStage <= 3) {
            println("[PipelineOrchestrator] Stage 3: Summarization starting")
            stateFlow.value = RecordingState.Summarizing
            try {
                withContext(Dispatchers.IO) {
                    val segments = repository.getSegmentsByMeetingId(meeting.id)
                    val settings = settingsRepository.load()
                    val rawSummary = llmProvider.summarize(segments, settings)
                    val summary = rawSummary.copy(meetingId = meeting.id)
                    repository.insertSummary(summary)
                    repository.updateMeetingSummaryModel(
                        meeting.id,
                        "${settings.llmProvider.name.lowercase()}/${settings.llmModel}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmUnavailableException) {
                // LLM not running — soft skip. The recording and transcript are preserved
                // so the user can re-run summarization later from the transcript screen.
                println("[PipelineOrchestrator] Stage 3: LLM unavailable (${e.message}) — skipping summarization")
                val updatedMeeting = withContext(Dispatchers.IO) {
                    repository.getMeetingById(meeting.id)
                } ?: meeting
                stateFlow.value = RecordingState.Complete(updatedMeeting, summarySkipped = true)
                return
            } catch (e: Exception) {
                lastFailedStage = 3
                println("[PipelineOrchestrator] Stage 3 FAILED: ${e.message}")
                stateFlow.value = RecordingState.Error(
                    stage = "Summarization",
                    message = e.message ?: "Summarization failed",
                    retryable = true,
                )
                return
            }
            println("[PipelineOrchestrator] Stage 3 complete: summarization done")
        }

        // ── Stage 4: Logseq Export ─────────────────────────────────────────
        if (startStage <= 4) {
            println("[PipelineOrchestrator] Stage 4: Logseq Export starting")
            stateFlow.value = RecordingState.Exporting
            try {
                withContext(Dispatchers.IO) {
                    val settings = settingsRepository.load()
                    if (settings.logseqWikiPath.isBlank()) return@withContext  // not configured — skip silently
                    val segments = repository.getSegmentsByMeetingId(meeting.id)
                    val summary = repository.getSummaryByMeetingId(meeting.id)
                        ?: return@withContext  // no summary yet — skip export
                    exporter.export(meeting, summary, segments, settings.logseqWikiPath)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailedStage = 4
                println("[PipelineOrchestrator] Stage 4 FAILED: ${e.message}")
                stateFlow.value = RecordingState.Error(
                    stage = "Export",
                    message = e.message ?: "Export failed",
                    retryable = true,
                )
                return
            }
            println("[PipelineOrchestrator] Stage 4 complete: export done")
        }

        val updatedMeeting = withContext(Dispatchers.IO) {
            repository.getMeetingById(meeting.id)
        } ?: meeting
        println("[PipelineOrchestrator] Pipeline complete for meetingId=${meeting.id}")
        stateFlow.value = RecordingState.Complete(updatedMeeting)
    }
}
