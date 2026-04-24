package com.meetingnotes.transcription

import com.meetingnotes.domain.audio.DiarizationBackend
import com.meetingnotes.domain.audio.DiarizationFailedException
import com.meetingnotes.domain.audio.DiarizationSegment
import com.meetingnotes.domain.audio.DiarizationTimeoutException
import com.meetingnotes.domain.audio.DiarizationUnavailableException
import com.meetingnotes.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [DiarizationBackend] implementation that runs speaker diarization via a Python subprocess.
 *
 * The subprocess invokes `native/diarize_session.py`, which uses pyannote.audio
 * (`pyannote/speaker-diarization-community-1`) to identify individual speakers in the
 * **system audio channel** (caller channel only).
 *
 * Results are merged into existing transcript segments by [applyDiarization], replacing
 * the generic "Caller" label with numbered variants ("Caller 1", "Caller 2", …).
 *
 * All errors surface as typed exceptions per the [DiarizationBackend] contract:
 * - [DiarizationUnavailableException] — Python or sidecar not found
 * - [DiarizationTimeoutException] — subprocess exceeded the hard timeout
 * - [DiarizationFailedException] — non-zero exit, unreadable output, or parse error
 *
 * Callers must catch these and fall back gracefully — diarization is non-fatal.
 */
class PyannoteDiarizationBackend : DiarizationBackend {

    /** JSON shape written by diarize_session.py: `[{"start": 0.0, "end": 2.3, "speaker": "SPEAKER_00"}]` */
    @Serializable
    private data class PyannoteSegment(val start: Double, val end: Double, val speaker: String)

    // ── DiarizationBackend ────────────────────────────────────────────────────

    /**
     * Returns true only when both a usable Python interpreter and the sidecar script
     * can be located. This check is fast (no model loading) and safe to call repeatedly.
     */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        resolvePython() != null && resolveScript() != null
    }

    /**
     * Runs `diarize_session.py` as a subprocess and returns the raw diarization segments.
     *
     * @throws DiarizationUnavailableException if Python or the sidecar script cannot be found
     * @throws DiarizationTimeoutException if the subprocess exceeds [timeoutMinutes]
     * @throws DiarizationFailedException for non-zero exit codes, empty output, or JSON parse errors
     */
    override suspend fun diarize(
        audioFilePath: String,
        hfToken: String,
        maxSpeakers: Int?,
        timeoutMinutes: Long,
    ): List<DiarizationSegment> = withContext(Dispatchers.IO) {

        // ── 1. Locate interpreter and sidecar ─────────────────────────────────
        val pythonCmd = resolvePython()
            ?: throw DiarizationUnavailableException("Python not found on PATH (tried python3, python)")

        val script = resolveScript()
            ?: throw DiarizationUnavailableException("diarize_session.py not found (checked next to JAR and CWD)")

        // ── 2. Temp file for JSON output ──────────────────────────────────────
        val outFile = File.createTempFile("diarize_out_", ".json")

        try {
            // ── 3. Build command ───────────────────────────────────────────────
            val cmd = mutableListOf(
                pythonCmd,
                script.absolutePath,
                "--audio", audioFilePath,
                "--out", outFile.absolutePath,
            )
            if (maxSpeakers != null) cmd += listOf("--max-speakers", maxSpeakers.toString())

            val processBuilder = ProcessBuilder(cmd)
            processBuilder.environment()["HF_TOKEN"] = hfToken

            val process = processBuilder.start()

            // ── 4. Drain stdout/stderr on background threads ───────────────────
            //    Without draining, the subprocess blocks once the OS pipe buffer fills,
            //    causing a deadlock between waitFor() and the subprocess writing output.
            val stdoutCapture = StringBuilder()
            val stderrCapture = StringBuilder()

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().use { r ->
                    r.forEachLine { stdoutCapture.appendLine(it) }
                }
            }.also { it.start() }

            val stderrThread = Thread {
                process.errorStream.bufferedReader().use { r ->
                    r.forEachLine { stderrCapture.appendLine(it) }
                }
            }.also { it.start() }

            // ── 5. Wait with hard timeout ──────────────────────────────────────
            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                process.destroy()
                stdoutThread.join(2_000)
                stderrThread.join(2_000)
                throw DiarizationTimeoutException(
                    "diarize_session.py exceeded ${timeoutMinutes}-minute timeout"
                )
            }

            stdoutThread.join()
            stderrThread.join()

            val exitCode = process.exitValue()
            val stderr = stderrCapture.toString().trim()
            val stdout = stdoutCapture.toString().trim()

            // ── 6. Check exit code ─────────────────────────────────────────────
            if (exitCode != 0) {
                throw DiarizationFailedException(
                    message = "diarize_session.py exited $exitCode${if (stderr.isNotEmpty()) ": $stderr" else ""}",
                    exitCode = exitCode,
                )
            }

            // ── 7. Parse output ────────────────────────────────────────────────
            //    Prefer the JSON file; fall back to stdout for minimal script variants.
            val jsonSource = when {
                outFile.exists() && outFile.length() > 0 -> outFile.readText()
                stdout.isNotEmpty() -> stdout
                else -> throw DiarizationFailedException(
                    "diarize_session.py produced no output",
                    exitCode = 0,
                )
            }

            try {
                Json.decodeFromString<List<PyannoteSegment>>(jsonSource)
                    .map { DiarizationSegment(startSec = it.start, endSec = it.end, speaker = it.speaker) }
            } catch (e: Exception) {
                throw DiarizationFailedException(
                    "Failed to parse diarization JSON: ${e.message}",
                    exitCode = 0,
                    cause = e,
                )
            }

        } finally {
            outFile.delete()
        }
    }

    /**
     * Merges [diarizationSegments] into [segments] by replacing every "Caller" label
     * with a numbered variant determined by maximum timestamp overlap.
     *
     * Speaker indices are 1-based and stable (assigned in order of first appearance on
     * the diarization timeline). Mic segments ("You") and segments with no overlap are
     * returned unchanged.
     */
    override fun applyDiarization(
        segments: List<TranscriptSegment>,
        diarizationSegments: List<DiarizationSegment>,
    ): List<TranscriptSegment> {
        if (diarizationSegments.isEmpty()) return segments

        val speakerIndexMap = buildSpeakerIndexMap(diarizationSegments)

        return segments.map { seg ->
            if (seg.speakerLabel != "Caller") return@map seg

            val segStartSec = seg.startMs / 1000.0
            val segEndSec = seg.endMs / 1000.0

            // Pick the diarization segment with the greatest overlap.
            val bestSpeaker = diarizationSegments
                .mapNotNull { d ->
                    val overlapStart = maxOf(segStartSec, d.startSec)
                    val overlapEnd = minOf(segEndSec, d.endSec)
                    val overlap = overlapEnd - overlapStart
                    if (overlap > 0.0) d.speaker to overlap else null
                }
                .maxByOrNull { it.second }
                ?.first

            val index = bestSpeaker?.let { speakerIndexMap[it] } ?: return@map seg
            seg.copy(speakerLabel = "Caller $index")
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the first available Python executable (`python3` preferred, `python` fallback),
     * or null if neither is on the PATH.
     */
    private fun resolvePython(): String? {
        for (candidate in listOf("python3", "python")) {
            try {
                val exitCode = ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                if (exitCode == 0) return candidate
            } catch (_: Exception) {
                // Executable not found; try next candidate.
            }
        }
        return null
    }

    /**
     * Locates `native/diarize_session.py` by checking:
     * 1. The directory containing the running JAR
     * 2. The current working directory
     */
    private fun resolveScript(): File? {
        val rel = "native/diarize_session.py"

        val jarParent = runCatching {
            File(
                PyannoteDiarizationBackend::class.java.protectionDomain.codeSource.location.toURI()
            ).parentFile
        }.getOrNull()

        if (jarParent != null) {
            val candidate = File(jarParent, rel)
            if (candidate.exists()) return candidate
        }

        val cwdCandidate = File(rel)
        return if (cwdCandidate.exists()) cwdCandidate else null
    }

    /**
     * Builds a speaker ID → 1-based index map, ordered by first appearance in the
     * diarization timeline (sorted ascending by [DiarizationSegment.startSec]).
     */
    private fun buildSpeakerIndexMap(segments: List<DiarizationSegment>): Map<String, Int> {
        val indexMap = LinkedHashMap<String, Int>()
        for (seg in segments.sortedBy { it.startSec }) {
            if (seg.speaker !in indexMap) {
                indexMap[seg.speaker] = indexMap.size + 1
            }
        }
        return indexMap
    }
}
