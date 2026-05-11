package com.meetingnotes.data.audio

import com.meetingnotes.domain.audio.DiarizationBackend
import com.meetingnotes.domain.audio.DiarizationSegment
import com.meetingnotes.domain.model.TranscriptSegment

/**
 * Stub for the ONNX Runtime-based diarization backend — not yet implemented.
 *
 * Declares the cross-platform portability path for Linux/Windows. When implemented,
 * this will use the ONNX Runtime Java SDK (com.microsoft.onnxruntime:onnxruntime)
 * to run a speaker diarization model without Python or CoreML.
 *
 * See ADR-001 in project_plans/agrapha-fluid-audio/decisions/ for the KMP fallback roadmap.
 */
class OnnxDiarizationBackend : DiarizationBackend {

    override suspend fun isAvailable(): Boolean = false

    override suspend fun areModelsAvailable(): Boolean = false

    override suspend fun downloadModels(): Unit = Unit

    override suspend fun diarize(
        audioFilePath: String,
        hfToken: String,
        maxSpeakers: Int?,
        timeoutMinutes: Long,
    ): List<DiarizationSegment> = throw NotImplementedError(
        "OnnxDiarizationBackend not yet implemented. See ADR-001 for KMP fallback roadmap."
    )

    override fun applyDiarization(
        segments: List<TranscriptSegment>,
        diarizationSegments: List<DiarizationSegment>,
    ): List<TranscriptSegment> = segments
}
