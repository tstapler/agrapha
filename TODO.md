# Agrapha — Project Status

**Last updated:** 2026-05-11
**Active branch:** `main`

---

## Summary

PR #1 was merged. All Linux parity work is complete. Three follow-on feature tracks are
substantially implemented but not fully wired: FluidAudio diarization (Stories 1–10, bridge
stubs pending real FluidAudio SPM URL), transcription/diarization improvements (all Kotlin
work done, WER harness pending), and Agrapha extraction (repo is live, CI/release pipeline
in place, working tree changes uncommitted).

**All 194 tests pass** (last confirmed on main at commit 11223d8).

---

## Uncommitted Working Tree Changes

The following files have unstaged modifications (not yet committed):

| File | Change |
|---|---|
| `.github/workflows/build.yml` | Added SPM cache + FluidAudio bridge build step (Story 7, FA plan) |
| `.github/workflows/release.yml` | Same FluidAudio bridge build additions |
| `.gitignore` | Added `native/FluidDiarizationBridge/.build/` and `libFluidDiarizationBridge.dylib` |
| `composeApp/build.gradle.kts` | Added `fluidAudioEnabled` property guard on `buildFluidDiarizationBridge` task |
| `native/FluidDiarizationBridge/.gitignore` | New untracked file |

These changes complete the Gradle + CI integration for the FluidAudio bridge (Stories 6 & 7
from `docs/tasks/fluida-audio-backends.md`). They should be committed before starting new work.

---

## FluidAudio Diarization Backends (fluida-audio-backends.md)

**State: Checkpoint A complete + Checkpoint B partially complete**

| Story | Description | Status |
|---|---|---|
| 1 | DiarizationBackend interface + exceptions (commonMain) | Done |
| 2 | PyannoteDiarizationBackend wrapper | Done |
| 3 | AudioAiBackendFactory + AppSettings.diarizationBackend | Done |
| 4 | PipelineQueueExecutor diarization block refactor | Done |
| 5 | FluidAudioDiarizationBackend skeleton (Kotlin) | Done |
| 6 | Swift SPM package FluidDiarizationBridgeJNI.swift | Done (stubs complete; FluidAudio URL is placeholder) |
| 7 | Gradle buildFluidDiarizationBridge task + CI integration | Done (uncommitted) |
| 8 | FluidAudioDiarizationBackend full implementation | Blocked (FluidAudio SPM URL must be real) |
| 9 | OnnxDiarizationBackend stub | Done |
| 10 | Settings UI backend selector + model download button | Done |
| 11 | Tests (DiarizationBackendContractTest, AudioAiBackendFactoryTest, PipelineQueueExecutorDiarizationTest) | Done |

**Blocker:** `native/FluidDiarizationBridge/Package.swift` references
`https://github.com/fluidinference/FluidAudio` as a placeholder. The build step runs
`continue-on-error: true` in CI because this URL does not yet resolve to a real SPM package.
When FluidAudio publishes a Swift Package, update `Package.swift` and remove the
`continue-on-error` flag.

---

## Transcription + Diarization Improvement (transcription-diarization-improvement.md)

**State: Story 1 and Story 3 complete; Story 2 partial; Stories 4 complete**

| Story/Task | Description | Status |
|---|---|---|
| Story 1 / Task 1.1 | N-gram repetition loop detection in WhisperService | Done |
| Story 1 / Task 1.2 | Buffer validation + CoreML status logging | Done (buffer validation in; backend logging partial — `detectedBackend` property exists, backend type detection not surfaced as "CoreML" vs "CPU" from loadLibraryOnce) |
| Story 1 / Task 1.3 | Configurable whisperInitialPrompt + noSpeechThreshold in AppSettings | Done |
| Story 2 / Task 2.1 | distil-large-v3 + distil-large-v3.5 added to WhisperModelDownloader | Done |
| Story 2 / Task 2.2 | WER measurement harness | Not started |
| Story 3 / Task 3.1 | diarize_session.py Python sidecar | Done (exists in native/) |
| Story 3 / Task 3.2 | DiarizationService.kt (subprocess lifecycle + JSON parsing) | Done |
| Story 3 / Task 3.3 | PipelineQueueExecutor diarization integration | Done |
| Story 3 / Task 3.4 | Diarization Settings UI (toggle + HF token + max speakers) | Done |
| Story 4 / Task 4.1 | TranscriptCorrectionService.kt (batched Ollama correction) | Done |
| Story 4 / Task 4.2 | Correction pipeline integration + settings toggle | Done |

**Remaining:** WER measurement harness (Task 2.2) — a JUnit test utility `@Ignore`d by default.
Low urgency; needed to gate Phase 2 Moonshine JNI decisions.

---

## Agrapha Extraction (agrapha-extraction.md)

**State: Stories 1–3 complete; Story 4 not applicable (monorepo cleanup)**

Agrapha is live at `tstapler/agrapha`. GitHub Actions CI and release pipeline are in place.
Story 4 tasks (monorepo cleanup) target the private monorepo and are out of scope for this repo.

---

## Open Bugs

No bugs tracked in `docs/bugs/` at this time.

Known risks and their current mitigation status:

| Risk / Bug | Severity | Status |
|---|---|---|
| BUG-TD-001: WhisperService SIGSEGV on empty audio buffer | Critical | Mitigated — buffer validation added in Task 1.2 (require non-empty, >= 1600 samples) |
| BUG-TD-002: CoreML ANE silent fallback on M4 + macOS 26.4 beta | High | Partially mitigated — `detectedBackend` property exists; `loadLibraryOnce()` needs to set it to "CoreML" vs "CPU" after branch selection |
| BUG-FA-001: JNI thread deadlock (DispatchSemaphore pattern) | High | Mitigated by design — DispatchSemaphore implemented in FluidDiarizationBridgeJNI.swift |
| BUG-FA-002: Dylib notarization gate (Gatekeeper) | High | Mitigated — `codesign -f -s -` in Gradle task; BackendUnavailableException on load failure |
| BUG-FA-003: FluidAudio SPM URL is a placeholder | High | Active — `Package.swift` and CI step both guarded; blocks Story 8 |
| BUG-TD-003: DiarizationService process timeout on long meetings | Medium | Mitigated — 60-min default configurable |
| BUG-AE-001: Gatekeeper distribution friction (no notarization) | High | Mitigated — cask caveats + README note the xattr workaround |
| BUG-FA-004: ANE memory contention on M1 | Medium | Mitigated — Mutex + limitedParallelism(1) in FluidAudioDiarizationBackend |
| BUG-TD-004: TranscriptCorrectionService hallucination risk | Medium | Mitigated by design — temperature=0.0, conservative prompt |

---

## Next After Uncommitted Work is Committed

Priority order for next work:

1. **Commit working tree changes** — CI/release YAML + build.gradle.kts + .gitignore cleanup from Stories 6 & 7 (fluida-audio-backends plan). 5-minute task, unblocks clean git state.
2. **CoreML backend type detection** (BUG-TD-002 / Task 1.2 completion) — `loadLibraryOnce()` does not currently set `detectedBackend` to "CoreML" vs "CPU" after loading. One small change in `WhisperService.kt`.
3. **WER measurement harness** (Task 2.2) — `WerBaseline.kt` JUnit utility, `@Ignore`d by default, enables Phase 2 Moonshine gating decision. Requires real meeting recordings with ground-truth transcripts.
4. **FluidAudio SPM URL resolution** — when FluidAudio publishes a Swift Package, update `Package.swift`, remove `continue-on-error` from CI, and run Story 8 full implementation.
5. **LIVE_CAPTIONS activation UI** — `LiveCaptionsOverlay` is wired; `DictationPlugin` exists in `Main.kt`; missing: a Settings toggle or hotkey to call `plugin.activate(LIVE_CAPTIONS, ...)`.

---

## Projects and Task Files

| File | Status | Description |
|---|---|---|
| `docs/tasks/linux-dictation-plugin.md` | Complete | All 22 stories done including Story 1.3 Linux CI |
| `docs/tasks/fluida-audio-backends.md` | Checkpoint A + B partial | Stories 1–7, 9–11 done; Story 8 blocked on FluidAudio SPM URL |
| `docs/tasks/transcription-diarization-improvement.md` | Mostly complete | Stories 1, 3, 4 done; Task 2.2 (WER harness) not started |
| `docs/tasks/agrapha-extraction.md` | Complete (in-repo scope) | Repo is live; Story 4 monorepo cleanup out of scope here |
| `project_plans/linux-dictation-plugin/` | Complete | Full 5-epic plan — all stories implemented |
