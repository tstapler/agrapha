package com.meetingnotes.transcription

import com.meetingnotes.domain.model.TranscriptSegment

/**
 * Pure utility for merging speaker diarization results into transcript segments.
 *
 * Extracted from [PyannoteDiarizationBackend] so the overlap-assignment and
 * speaker-index logic can be tested without a subprocess dependency.
 */
class DiarizationService {

    data class DiarizationSegment(val start: Double, val end: Double, val speaker: String)

    /**
     * Replaces "Caller" labels in [segments] with numbered variants ("Caller 1", "Caller 2", …)
     * determined by maximum timestamp overlap with [diarizationSegments].
     *
     * Speaker indices are 1-based and assigned in order of first appearance on the
     * diarization timeline. Segments with speaker label "You" and segments with no
     * overlap are returned unchanged.
     */
    fun applyDiarization(
        segments: List<TranscriptSegment>,
        diarizationSegments: List<DiarizationSegment>,
    ): List<TranscriptSegment> {
        if (diarizationSegments.isEmpty()) return segments

        val speakerIndexMap = buildSpeakerIndexMap(diarizationSegments)

        return segments.map { seg ->
            if (seg.speakerLabel != "Caller") return@map seg

            val segStartSec = seg.startMs / 1000.0
            val segEndSec = seg.endMs / 1000.0

            val bestSpeaker = diarizationSegments
                .mapNotNull { d ->
                    val overlapStart = maxOf(segStartSec, d.start)
                    val overlapEnd = minOf(segEndSec, d.end)
                    val overlap = overlapEnd - overlapStart
                    if (overlap > 0.0) d.speaker to overlap else null
                }
                .maxByOrNull { it.second }
                ?.first

            val index = bestSpeaker?.let { speakerIndexMap[it] } ?: return@map seg
            seg.copy(speakerLabel = "Caller $index")
        }
    }

    private fun buildSpeakerIndexMap(segments: List<DiarizationSegment>): Map<String, Int> {
        val indexMap = LinkedHashMap<String, Int>()
        for (seg in segments.sortedBy { it.start }) {
            if (seg.speaker !in indexMap) {
                indexMap[seg.speaker] = indexMap.size + 1
            }
        }
        return indexMap
    }
}
