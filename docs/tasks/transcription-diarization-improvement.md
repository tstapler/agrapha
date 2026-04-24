# Feature Plan: Transcription + Diarization Improvement

Created: 2026-04-15
Spec: `project_plans/transcription-diarization-improvement/`
ADRs: `project_plans/transcription-diarization-improvement/decisions/`

---

## Epic Overview

### User Value
Users in multi-party meetings currently see all remote participants labeled as a single "Caller", making transcripts nearly useless for attribution. After this feature, transcripts correctly identify individual callers ("Caller 1", "Caller 2") and contain fewer transcription errors. Users in 1:1 meetings benefit from reduced hallucination rate and better ASR accuracy.

### Success Metrics
- Hallucination rate reduced: n-gram repetition loops eliminated in production (currently #1 complaint)
- Multi-speaker attribution: meetings with 3+ participants produce ≥2 distinct "Caller N" labels
- WER: distil-large-v3 A/B comparison yields ≤10% WER on internal meeting sample (baseline measurement)
- Transcript correction: optional Ollama pass reduces obvious ASR errors on internal sample

### Scope
**In scope (Phase 1):**
- Hallucination defense hardening (n-gram loop detection, buffer validation, CoreML status logging)
- distil-large-v3 model support as A/B option alongside large-v3
- Post-session pyannote diarization via Python subprocess (system audio channel only)
- WER measurement harness on real meeting recordings
- Optional Ollama transcript correction (post-session, user-toggleable)
- Settings UI for new diarization and correction toggles

**Out of scope (Phase 1):**
- Moonshine JNI bridge (deferred to Phase 2 per ADR-003)
- Live speaker labels during recording (deferred per ADR-001)
- Cloud LLM correction (privacy constraint)
- Multi-language support changes

### Constraints
- All processing on-device; no cloud calls for core pipeline
- Must integrate with existing `WhisperService`, `DesktopTranscriptionUseCase`, `PipelineOrchestrator`
- Memory: large-v3 uses ~4 GB; pyannote subprocess runs after recording (not concurrent); Ollama correction is sequential post-diarization
- Python 3.10+ must be present or bundled (gating question: see Open Questions)

---

## Architecture Decisions

| ADR | File | Decision |
|---|---|---|
| ADR-001 | `decisions/ADR-001-post-hoc-diarization.md` | Post-hoc full-session diarization over chunk-level (global speaker identity, zero reconciliation code) |
| ADR-002 | `decisions/ADR-002-python-subprocess-diarization.md` | Python subprocess (pyannote) over JVM-native ONNX (complete pipeline, production-proven, ~16-20% DER) |
| ADR-003 | `decisions/ADR-003-defer-moonshine-jni.md` | Moonshine JNI bridge deferred to Phase 2 (gate on WER baseline measurement) |

---

## Story Breakdown

### Story 1: Hallucination Defense & ASR Hardening [3–4 days]

**User value:** Eliminates repetition loops that produce unusable transcript segments like "Thank you. Thank you. Thank you..." — the most reported quality failure mode.

**Acceptance criteria:**
- Repetition-loop transcription segments are detected before persistence and either dropped or flagged
- Sample buffer with mismatched sample rate is rejected with a clear error message before `whisper.full()` is called
- CoreML backend status (ANE/Metal/CPU) is logged at model load time
- `noSpeechThold` is configurable and properly tuned to reduce false-positive silence transcriptions

#### Task 1.1: N-gram Repetition Detection in WhisperService [2h]

**Objective:** Add post-inference filtering that detects repetition-loop hallucinations and drops them before segment list is returned.

**Context boundary:**
- Primary: `composeApp/src/desktopMain/kotlin/transcription/WhisperService.kt` (~310 lines)
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/DualChannelDiarizer.kt` (for context on output)

**Prerequisites:**
- Understand the `transcribeUnderLock` method's segment filtering loop (lines 152–178)
- Know that `isKnownHallucination()` already exists for exact-match filtering — this extends it

**Implementation approach:**
1. Add `private fun isRepetitionLoop(text: String): Boolean` — split text into 3–5 word n-grams, detect if any n-gram appears ≥3 times consecutively (covers "thank you. thank you. thank you." and "blah blah blah" patterns)
2. Add `filteredRepeat` counter alongside `filteredHallu` in the segment loop
3. Call `isRepetitionLoop(text)` in the filter block; increment counter and `continue`
4. Include repeat count in the existing filter stats log line
5. Add a secondary check: if a segment's text is >90% identical to the preceding segment text (Levenshtein or simple substring check), drop as repetition

**Validation strategy:**
- Unit test in `WhisperModelDownloaderTest.kt` or new `WhisperServiceFilterTest.kt`:
  - Input: `"Thank you. Thank you. Thank you."` → filtered
  - Input: `"The build is failing because the pipeline runs too slowly."` → kept
  - Input: `"And and and and and and we should"` → filtered
  - Input: `"Let me rephrase. Let me say that differently."` → kept (different content)

**INVEST check:**
- Independent: no external coordination
- Negotiable: n-gram size and threshold are adjustable
- Valuable: eliminates #1 production failure mode
- Estimable: 2h with high confidence
- Small: one method added, one loop modified
- Testable: pure function, unit-testable

---

#### Task 1.2: Sample Buffer Validation + CoreML Status Logging [2h]

**Objective:** Prevent JVM SIGSEGV from bad audio input; surface CoreML backend degradation.

**Context boundary:**
- Primary: `composeApp/src/desktopMain/kotlin/transcription/WhisperService.kt`

**Prerequisites:**
- Understand `loadAudioSamples()` — it reads raw PCM and returns FloatArray
- Understand `loadLibraryOnce()` — CoreML path is the `libwhisperjni-coreml.dylib` branch

**Implementation approach:**
1. Add buffer validation in `transcribeUnderLock` before `whisper.fullWithState()` call:
   - Assert `samples.isNotEmpty()` → throw `IllegalArgumentException("Empty audio buffer for $audioPath")`
   - Assert `samples.size >= 1600` (minimum 100ms at 16kHz) → throw with helpful message
   - Assert no NaN/Inf values in first 100 samples (cheap sanity check): `samples.take(100).none { it.isNaN() || it.isInfinite() }`
2. In `loadLibraryOnce()`, after loading the CoreML dylib, log which backend was selected with a distinguishing prefix: `[WhisperService] backend=COREML_ANE` vs `backend=CPU_FALLBACK`
3. Add a `backendType: String` property on `WhisperService` (lazily set during `loadLibraryOnce()`) so tests and UI can inspect it

**Validation strategy:**
- Unit test: construct FloatArray of zeros with size 800 → verify `IllegalArgumentException` thrown
- Unit test: construct valid FloatArray → verify no exception
- Integration: run on a real model and confirm backend log appears in stderr

**INVEST check:** All criteria met. Single concern (defensive validation). 2h.

---

#### Task 1.3: Configurable initialPrompt and noSpeechThold [1h]

**Objective:** Make `initialPrompt` and `noSpeechThold` configurable rather than hardcoded, supporting domain-specific transcription.

**Context boundary:**
- Primary: `composeApp/src/commonMain/kotlin/domain/model/AppSettings.kt` (~48 lines)
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/WhisperService.kt` (params block)

**Implementation approach:**
1. Add to `AppSettings`: `val whisperInitialPrompt: String = "This is a software engineering meeting."` and `val whisperNoSpeechThreshold: Float = 0.7f`
2. Thread `AppSettings` through to `WhisperService.transcribe()` or pass the values via `WhisperParams` DTO
3. Update `WhisperService.transcribeUnderLock()` to use `settings.whisperInitialPrompt` and `settings.whisperNoSpeechThreshold` from params rather than hardcoded literals
4. Update `SettingsRepository` load/save to persist the new fields

**Validation strategy:**
- Unit test: `AppSettings` with custom `initialPrompt` is serialized and deserialized correctly (existing `SettingsViewModelTest.kt` pattern)
- Verify `WhisperService` uses the new values: pass mock settings with different prompt, confirm params object has correct value

**INVEST check:** All criteria met. Single concern (settings schema). 1h.

---

### Story 2: distil-large-v3 Model Support + WER Baseline [2–3 days]

**User value:** Provides a model option with lower hallucination rate (2.1% less insertion error) and −2 GB RAM at equivalent WER. Establishes the production WER baseline needed to gate Phase 2 decisions.

**Acceptance criteria:**
- distil-large-v3 GGML model can be downloaded and selected in the app
- App correctly handles the model's smaller attention head count (distil-large-v3 uses different GGML structure from large-v3)
- A WER measurement script produces a comparable metric on 3+ real meeting recordings

#### Task 2.1: Add distil-large-v3 to Model Enum + Downloader [2h]

**Objective:** Add distil-large-v3 as a downloadable model option.

**Context boundary:**
- Primary: `composeApp/src/commonMain/kotlin/domain/model/AppSettings.kt`
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/WhisperModelDownloader.kt`
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/ModelDownloadManager.kt`

**Prerequisites:**
- Find the current GGML distil-large-v3 download URL from the whisper.cpp model repository (huggingface.co/ggerganov/whisper.cpp or distil-whisper/distil-large-v3-ggml)
- Verify the model filename convention used by ModelDownloadManager

**Implementation approach:**
1. Add `DISTIL_LARGE_V3("distil-large-v3 (1.45 GB, recommended)", "ggml-distil-large-v3.bin", 1_450_000_000L)` to `WhisperModelSize` enum with a `(recommended)` hint
2. Add the download URL to `WhisperModelDownloader`'s URL map
3. Confirm `ModelDownloadManager` handles the new enum value without a `when` exhaustive check failure
4. Test download flow (existing `WhisperModelDownloaderTest.kt` integration test pattern)

**Validation strategy:**
- Unit test: `WhisperModelSize.DISTIL_LARGE_V3.fileName` is non-null and matches expected pattern
- Integration test: downloader resolves the correct URL for the new model
- Manual: model downloads, loads in WhisperService, transcribes a 30s sample

**INVEST check:** All criteria met. Model enum + URL addition. 2h.

---

#### Task 2.2: WER Measurement Harness [3h]

**Objective:** Create a test harness that measures WER on real meeting recordings to establish a baseline and enable A/B comparison between models.

**Context boundary:**
- New: `composeApp/src/desktopTest/kotlin/transcription/WerBaseline.kt` (test utility)
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/WhisperService.kt`
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/AudioPreprocessor.kt`

**Prerequisites:**
- 3–5 real meeting recordings with known ground-truth transcripts (manually created or corrected)
- Understand how `AudioPreprocessor.splitChannels()` works

**Implementation approach:**
1. Write `WerBaseline.kt` — a JUnit test class annotated `@Ignore` by default (manual activation only). Takes a list of WAV paths + reference transcripts from a local config file
2. Implement simple WER: `wer(hypothesis, reference) = (S + D + I) / N` where S=substitutions, D=deletions, I=insertions, N=reference word count. Use a basic dynamic programming edit distance
3. For each recording: run full preprocessing + WhisperService transcription with large-v3, then distil-large-v3; compute WER per model; log results as a markdown table to stderr
4. Store baseline results as a comment/constant so regressions are detectable in CI

**Validation strategy:**
- Test harness produces numeric WER output for at least one recording
- distil-large-v3 WER within ±2% of large-v3 WER on the same recordings (validates the benchmark claims on real data)

**INVEST check:** Isolated test utility. Negotiable (WER algorithm). 3h.

---

### Story 3: pyannote Speaker Diarization via Python Subprocess [4–5 days]

**User value:** Multi-party meetings correctly identify individual callers. A 5-person standup produces "You", "Caller 1", "Caller 2", "Caller 3", "Caller 4" labels instead of all remote speakers blended as "Caller".

**Acceptance criteria:**
- Post-session diarization runs as Stage 1 in PipelineOrchestrator (between Transcription and Summarization)
- System audio channel segments are relabeled from "Caller" to "Caller 1", "Caller 2", etc.
- Mic channel segments remain labeled "You"
- If Python/pyannote is unavailable, pipeline skips diarization silently (graceful degradation)
- Diarization can be enabled/disabled in Settings
- HuggingFace token is configurable in Settings

#### Task 3.1: Python Diarization Script [2h]

**Objective:** Write the Python sidecar script that runs pyannote and outputs JSON.

**Context boundary:**
- New file: `native/diarize_session.py`

**Prerequisites:**
- pyannote.audio 3.x API: `from pyannote.audio import Pipeline`
- HuggingFace token usage: `use_auth_token=os.environ["HF_TOKEN"]`

**Implementation approach:**
1. Create `native/diarize_session.py`:
   ```python
   #!/usr/bin/env python3
   # Usage: diarize_session.py --audio path.wav --out path.json [--hf-token TOKEN] [--max-speakers N]
   ```
2. Load `pyannote/speaker-diarization-community-1` pipeline with the provided HF token
3. Run `pipeline(audio_path, max_speakers=max_speakers)` — constrain speaker count if provided
4. Convert RTTM output to JSON: `[{"start": float, "end": float, "speaker": "SPEAKER_00"}, ...]`
5. Write JSON to `--out` path; exit 0 on success, non-zero on failure
6. Add error handling: model not found → exit 2; HF token invalid → exit 3; audio unreadable → exit 4
7. Print progress to stderr (captured by Kotlin for logging)

**Validation strategy:**
- Manual: run `python diarize_session.py --audio test.wav --out out.json --hf-token $HF_TOKEN`
- Verify JSON output has correct structure
- Verify exit code 3 on invalid HF token

**INVEST check:** All criteria met. Single Python script. 2h.

---

#### Task 3.2: DiarizationService.kt [3h]

**Objective:** Kotlin service that manages the Python subprocess lifecycle and parses the JSON output.

**Context boundary:**
- New: `composeApp/src/desktopMain/kotlin/transcription/DiarizationService.kt`
- Supporting: `composeApp/src/commonMain/kotlin/domain/model/TranscriptSegment.kt`

**Prerequisites:**
- Understanding of `ProcessBuilder` API in Kotlin/JVM
- JSON parsing via `kotlinx.serialization` (already in build.gradle.kts)
- `TranscriptSegment.speakerLabel` field

**Implementation approach:**
1. Define `data class DiarizationSegment(val start: Double, val end: Double, val speaker: String)`
2. `DiarizationService.diarize(sysAudioPath: Path, meetingId: String, hfToken: String, maxSpeakers: Int? = null): DiarizationResult` where `DiarizationResult` is a sealed class: `Success(segments: List<DiarizationSegment>)`, `Unavailable(reason: String)`, `Failed(exitCode: Int, stderr: String)`
3. Resolve Python path: try `python3`, then `python`, fail fast if neither found → return `Unavailable("Python not found")`
4. Resolve script path: look for `diarize_session.py` relative to app resource dir; embed as a classpath resource if needed
5. Use `ProcessBuilder` with `redirectErrorStream(false)`; set `HF_TOKEN` env var from `hfToken`
6. Wait with timeout (configurable, default 30 min); read stdout (JSON) and stderr (logs) concurrently on separate threads to prevent blocking
7. Parse JSON via `Json.decodeFromString<List<DiarizationSegment>>(stdout)`
8. Map `DiarizationResult.Success` → apply speaker labels to transcript segments

**Merge logic** (`applyDiarization`): Given `List<TranscriptSegment>` (all segments for the meeting) and `List<DiarizationSegment>`, for each segment where `speakerLabel == "Caller"`:
- Find the `DiarizationSegment` with the largest time overlap with the transcript segment's `[startMs/1000, endMs/1000]`
- Replace `speakerLabel` with `"Caller ${speakerIndex+1}"` where `speakerIndex` is derived from a stable SPEAKER_0N → index mapping

**Validation strategy:**
- Unit test: `applyDiarization` with 3 mock transcript segments and 2 diarization segments correctly assigns "Caller 1" and "Caller 2"
- Unit test: overlap computation is correct at segment boundaries
- Unit test: Python not found → returns `Unavailable`
- Unit test: script exits with code 3 → returns `Failed(3, ...)`

**INVEST check:** All criteria met. New class, well-bounded. 3h.

---

#### Task 3.3: Integrate DiarizationService into PipelineOrchestrator [2h]

**Objective:** Add diarization as Stage 1 in the post-recording pipeline (shifting summarization to Stage 2, export to Stage 3).

**Context boundary:**
- Primary: `composeApp/src/commonMain/kotlin/domain/PipelineOrchestrator.kt` (~182 lines)
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/DiarizationService.kt`
- Supporting: `composeApp/src/commonMain/kotlin/domain/model/AppSettings.kt`
- Supporting: `composeApp/src/commonMain/kotlin/domain/model/RecordingState.kt`

**Prerequisites:**
- Understand existing stage numbering and `lastFailedStage` retry logic
- Understand `RecordingState` sealed class (needs new `Diarizing` state)

**Implementation approach:**
1. Add `RecordingState.Diarizing` to the sealed class
2. Add `diarizationService: DiarizationService?` as an optional constructor parameter on `PipelineOrchestrator`
3. Add Stage 1 between Transcription (now Stage 0) and Summarization (now Stage 2):
   ```kotlin
   if (startStage <= 1 && diarizationService != null && settings.diarizationEnabled) {
       stateFlow.value = RecordingState.Diarizing
       // get sysAudioPath from meeting.audioFilePath → split path convention
       // call diarizationService.diarize(...)
       // on Success: repository.updateSegmentSpeakerLabels(meetingId, labelMap)
       // on Unavailable/Failed: log + continue (graceful degradation)
   }
   ```
4. Renumber `lastFailedStage` references accordingly (0=Transcription, 1=Diarization, 2=Summarization, 3=Export)
5. Add `MeetingRepository.updateSegmentSpeakerLabels(meetingId: String, labelMap: Map<String, String>)` if not already present

**Validation strategy:**
- Unit test in `PipelineOrchestratorTest.kt`: mock `DiarizationService` returning `Success` → verify `stateFlow` emits `Diarizing`
- Unit test: mock returning `Unavailable` → verify pipeline continues to Summarization without error
- Unit test: `diarizationEnabled = false` in settings → Diarization stage is skipped entirely

**INVEST check:** Well-bounded integration. Negotiable (diarization is optional/injectable). 2h.

---

#### Task 3.4: Diarization Settings (AppSettings + SettingsUI) [2h]

**Objective:** Add diarization toggle and HuggingFace token input to settings.

**Context boundary:**
- Primary: `composeApp/src/commonMain/kotlin/domain/model/AppSettings.kt`
- Primary: `composeApp/src/desktopMain/kotlin/ui/settings/SettingsScreen.kt`
- Supporting: `composeApp/src/desktopMain/kotlin/ui/settings/SettingsViewModel.kt`

**Implementation approach:**
1. Add to `AppSettings`:
   - `val diarizationEnabled: Boolean = false`
   - `val huggingFaceToken: String = ""`
   - `val diarizationMaxSpeakers: Int? = null` (null = auto-detect)
2. Add settings section "Speaker Identification" in `SettingsScreen.kt`:
   - Toggle: "Identify individual speakers (requires Python + pyannote)"
   - Text field: "HuggingFace token" (password masking)
   - Int field: "Max speakers (leave blank for auto)"
   - Help text: "First run downloads ~350 MB model. Requires a free HuggingFace account."
3. Add ViewModel events for the new fields

**Validation strategy:**
- Unit test: new fields serialize/deserialize correctly
- Manual: toggle diarization, enter HF token, verify persisted in settings

**INVEST check:** All criteria met. UI + settings schema. 2h.

---

### Story 4: Transcript Correction via Ollama [2–3 days]

**User value:** Obvious ASR errors in the transcript ("the piper line" → "the pipeline", "deeploy" → "deploy") are automatically corrected before summarization, improving summary quality.

**Acceptance criteria:**
- An optional transcript correction step runs after diarization and before summarization
- Correction uses the existing Ollama provider infrastructure (`localhost:11434`)
- Correction does not rephrase or summarize — only fixes clear ASR errors
- Correction can be enabled/disabled in Settings independently of the summarization LLM

#### Task 4.1: TranscriptCorrectionService.kt [3h]

**Objective:** A focused service that corrects ASR errors in transcript segments using a locally-hosted LLM.

**Context boundary:**
- New: `composeApp/src/desktopMain/kotlin/transcription/TranscriptCorrectionService.kt`
- Supporting: `composeApp/src/desktopMain/kotlin/data/llm/OllamaProvider.kt` (HTTP client pattern)
- Supporting: `composeApp/src/commonMain/kotlin/domain/model/TranscriptSegment.kt`

**Prerequisites:**
- Understand `OllamaProvider` HTTP call pattern (Ktor client, `/api/generate` endpoint)
- Target model: `phi3:mini` (2.3 GB Q4) or `llama3.2:3b` (configurable) — smaller than summarization model

**Implementation approach:**
1. Define `TranscriptCorrectionService(httpClient: HttpClient)` with:
   ```kotlin
   suspend fun correct(
       segments: List<TranscriptSegment>,
       model: String,
       baseUrl: String,
   ): List<TranscriptSegment>
   ```
2. Group segments into batches of ~20 (to stay within context window). For each batch, build prompt:
   ```
   System: You are an ASR transcript corrector. Fix ONLY clear speech recognition errors.
           Do NOT rephrase, summarize, or change meaning. Return corrected text for each line.
           Preserve speaker labels and timestamps exactly.
   User: Correct the following transcript segments (one per line, format: INDEX|TEXT):
         0|The piper line is failing
         1|We need to deeploy the new version
   Assistant: 0|The pipeline is failing
              1|We need to deploy the new version
   ```
3. Parse response: split on `|`, match by index back to original segment list, return updated segments
4. Use `temperature=0.0` for determinism; `num_predict=512` per batch
5. On error/timeout: return original segments unmodified (fail-safe)

**Validation strategy:**
- Unit test: mock HTTP client returns corrected transcript → segments updated correctly
- Unit test: mock HTTP client returns malformed response → original segments returned unchanged
- Unit test: empty segment list → returns immediately without HTTP call

**INVEST check:** All criteria met. New service, HTTP-backed, pure function interface. 3h.

---

#### Task 4.2: Integrate Correction into Pipeline + Settings [2h]

**Objective:** Wire `TranscriptCorrectionService` into `PipelineOrchestrator` as an optional post-diarization stage; add settings toggle.

**Context boundary:**
- Primary: `composeApp/src/commonMain/kotlin/domain/PipelineOrchestrator.kt`
- Primary: `composeApp/src/commonMain/kotlin/domain/model/AppSettings.kt`
- Supporting: `composeApp/src/desktopMain/kotlin/transcription/TranscriptCorrectionService.kt`
- Supporting: `composeApp/src/desktopMain/kotlin/ui/settings/SettingsScreen.kt`

**Implementation approach:**
1. Add `val transcriptCorrectionEnabled: Boolean = false` and `val correctionModel: String = "phi3:mini"` to `AppSettings`
2. Add `correctionService: TranscriptCorrectionService?` to `PipelineOrchestrator` constructor
3. Add Stage 1.5 (between Diarization and Summarization) in the orchestrator:
   ```kotlin
   if (startStage <= 2 && correctionService != null && settings.transcriptCorrectionEnabled) {
       stateFlow.value = RecordingState.CorrectingTranscript
       val segments = repository.getSegmentsByMeetingId(meeting.id)
       val corrected = correctionService.correct(segments, settings.correctionModel, settings.llmBaseUrl)
       repository.replaceSegments(meeting.id, corrected)
   }
   ```
4. Add `RecordingState.CorrectingTranscript` to the sealed class
5. Add settings UI: "Transcript error correction" section with toggle + model name field

**Validation strategy:**
- Unit test: correction enabled + service returns corrected segments → `repository.replaceSegments` called
- Unit test: correction disabled → service never called, original segments unchanged
- Manual: enable correction with `phi3:mini`, run on a meeting recording, verify corrections visible in transcript screen

**INVEST check:** All criteria met. Well-bounded integration point. 2h.

---

## Known Issues

### Bug 001: WhisperService SIGSEGV on Empty Audio Buffer [SEVERITY: Critical]
**Description:** whisper-jni passes the FloatArray directly to native C++. If the array is empty (file read failure, zero-length WAV), `whisper.full()` triggers a native SIGSEGV that kills the JVM with no recoverable exception. This is a documented issue in whisper-jni.
**Files affected:** `WhisperService.kt` — `loadAudioSamples()` and `transcribeUnderLock()`
**Mitigation:** Task 1.2 adds buffer validation before calling `whisper.fullWithState()`
**Prevention:** Validate at the input boundary; never trust that `loadAudioSamples()` returns a non-empty array

### Bug 002: CoreML ANE Silent Fallback on M4 + Recent macOS [SEVERITY: High]
**Description:** On M4 + macOS 26.4 beta (and some 15.x releases), ANE compilation fails silently and whisper.cpp falls back to Metal (2–3x slower). The `--optimize-ane True` flag is documented as broken in whisper.cpp source. Users experience sudden transcription slowdowns with no explanation.
**Files affected:** `WhisperService.kt` — `loadLibraryOnce()`
**Mitigation:** Task 1.2 adds backend status logging
**Prevention:** Benchmark model load time at startup; alert user if ANE expected but Metal detected

### Bug 003: DiarizationService Process Timeout on Long Meetings [SEVERITY: Medium]
**Description:** pyannote processing time scales with audio duration. A 2-hour meeting may take 10–30 min on Apple Silicon CPU. If the `ProcessBuilder` timeout is too short, diarization is killed mid-run and the meeting loses multi-speaker attribution silently.
**Files affected:** `DiarizationService.kt` (to be created)
**Mitigation:** Make timeout configurable (default 60 min); log estimated remaining time from pyannote stderr
**Prevention:** Communicate progress to UI (`RecordingState.Diarizing(progressPercent)`) so users see that it is running

### Bug 004: TranscriptCorrectionService Hallucination Risk [SEVERITY: Medium]
**Description:** A local LLM at temperature=0 can still hallucinate "corrections" that introduce errors rather than fix them — particularly for technical terms, proper nouns, and code identifiers that don't appear in the model's training data.
**Files affected:** `TranscriptCorrectionService.kt` (to be created)
**Mitigation:** `temperature=0.0`, conservative prompt ("fix ONLY clear ASR errors, do not rephrase"), batch size ≤20 segments to minimize context drift
**Prevention:** Run on a test set first; compare WER before/after correction to verify the service helps not hurts

### Bug 005: pyannote NaN Embeddings on Short Segments [SEVERITY: Low]
**Description:** pyannote v3.x emits NaN embeddings for audio segments shorter than ~0.3s (issue #1961). This causes a ValueError in the clustering step.
**Files affected:** `diarize_session.py` (to be created)
**Mitigation:** Pass `min_duration_on=0.5` and `min_duration_off=0.1` to pyannote pipeline instantiation
**Prevention:** Validate pyannote output before writing JSON; catch ValueError and retry with relaxed min_duration

---

## Dependency Visualization

```
Story 1 (Hardening) ─────────────────────────────────────────────┐
  Task 1.1 [n-gram filter]                                        │
  Task 1.2 [buffer validation + CoreML logging]                   │
  Task 1.3 [configurable prompt/threshold]                        │
                                                                  │
Story 2 (distil model) ──────────────────────────────────────────┤
  Task 2.1 [enum + downloader]                                    │
  Task 2.2 [WER harness] ← requires Task 2.1 complete            │
                                                                  │
Story 3 (diarization) ───────────────────────────────────────────┤ → Integration Checkpoint 1
  Task 3.1 [diarize_session.py]                                   │   All 4 stories complete
  Task 3.2 [DiarizationService.kt] ← requires 3.1               │
  Task 3.3 [PipelineOrchestrator integration] ← requires 3.2    │
  Task 3.4 [Settings UI] ← can parallel 3.2/3.3                 │
                                                                  │
Story 4 (correction) ────────────────────────────────────────────┘
  Task 4.1 [TranscriptCorrectionService.kt]
  Task 4.2 [Pipeline integration + settings] ← requires 4.1

Stories 1, 2, first tasks of 3 and 4 can run in parallel.
Task 3.3 requires Task 3.2. Task 4.2 requires Task 4.1.
Story 3 Task 3.3 should be completed before Story 4 Task 4.2 (stage ordering).
```

---

## Integration Checkpoints

**After Story 1:** WhisperService hardened — no repetition loops in production transcripts; CoreML status visible in logs; prompt configurable via settings.

**After Story 2:** distil-large-v3 available in model picker; WER baseline established on internal recordings. Decision gate: is large-v3 WER a real problem on production audio? If WER < 8% on real recordings, Phase 2 Moonshine work is lower priority.

**After Story 3:** Multi-speaker meetings produce labeled transcripts. Demo: run a 3-party standup recording through the updated pipeline, verify 3+ speaker labels in transcript screen.

**After Story 4 (full feature complete):** End-to-end pipeline: Record → Transcribe (distil/large-v3) → Diarize (pyannote) → Correct (optional Ollama) → Summarize → Export. All on-device, graceful degradation at each step.

---

## Context Preparation Guides

**For Task 1.1 (repetition detection):**
- Load: `WhisperService.kt` lines 150–190 (segment filter loop)
- Know: `isKnownHallucination()` pattern; `filteredHallu` counter pattern

**For Task 3.2 (DiarizationService):**
- Load: `DualChannelDiarizer.kt` (shows how WhisperService output is currently used)
- Load: `TranscriptSegment.kt` (data model to merge into)
- Load: `OllamaProvider.kt` lines 27–65 (ProcessBuilder-adjacent: shows Ktor HTTP pattern; DiarizationService uses ProcessBuilder instead)

**For Task 3.3 (PipelineOrchestrator integration):**
- Load: `PipelineOrchestrator.kt` (entire file, ~182 lines)
- Load: `RecordingState.kt` (sealed class to extend)
- Know: Stage numbering is 0-indexed; `lastFailedStage` drives retry logic

**For Task 4.1 (TranscriptCorrectionService):**
- Load: `OllamaProvider.kt` (entire file — HTTP client, request/response models, error handling patterns)
- Load: `TranscriptSegment.kt`
- Know: batch prompting pattern; `temperature=0.0` for determinism

---

## Success Criteria

- [ ] All Story 1 tasks complete: n-gram loop detection eliminating repetitions in 3+ test cases; buffer validation preventing SIGSEGV on empty input; CoreML backend logged at startup
- [ ] All Story 2 tasks complete: distil-large-v3 downloadable and selectable; WER baseline measured on ≥3 real recordings
- [ ] All Story 3 tasks complete: post-session diarization produces "Caller 1/2/N" labels on multi-party meetings; graceful degradation when Python/pyannote unavailable; HF token configurable in Settings
- [ ] All Story 4 tasks complete (optional): transcript correction toggle in Settings; Ollama correction runs between diarization and summarization; WER improved or equal after correction pass
- [ ] No existing tests broken by changes
- [ ] Memory: concurrent diarization + summarization not attempted (sequential scheduling verified)
- [ ] Phase 2 gate: WER measurements documented and linked from here with decision recommendation
