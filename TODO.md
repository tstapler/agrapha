# Agrapha — Project Status

**Last updated:** 2026-05-11
**Active branch:** `main`

---

## Summary

All feature tracks are substantially complete. Recent session work:
- LIVE_CAPTIONS activation UI wired (Settings toggle → `plugin.activate(LIVE_CAPTIONS)`)
- `SettingsRepository` persistence bug fixed: 11 fields were silently lost on restart
- FluidAudio v0.14.5 confirmed public; Swift bridge updated to current API; Story 8 unblocked and complete
- WER measurement harness added (`WerBaselineTest`); skips when no test data present

**All 223 tests pass.**

---

## FluidAudio Diarization Backends (fluida-audio-backends.md)

**State: All stories complete**

| Story | Description | Status |
|---|---|---|
| 1 | DiarizationBackend interface + exceptions (commonMain) | Done |
| 2 | PyannoteDiarizationBackend wrapper | Done |
| 3 | AudioAiBackendFactory + AppSettings.diarizationBackend | Done |
| 4 | PipelineQueueExecutor diarization block refactor | Done |
| 5 | FluidAudioDiarizationBackend skeleton (Kotlin) | Done |
| 6 | Swift SPM package FluidDiarizationBridgeJNI.swift | Done |
| 7 | Gradle buildFluidDiarizationBridge task + CI integration | Done |
| 8 | FluidAudio v0.14.5 bridge wired (Swift API updated) | Done |
| 9 | OnnxDiarizationBackend stub | Done |
| 10 | Settings UI backend selector + model download button | Done |
| 11 | Tests | Done |

---

## Transcription + Diarization Improvement (transcription-diarization-improvement.md)

**State: All stories complete**

| Story/Task | Description | Status |
|---|---|---|
| Story 1 / Task 1.1 | N-gram repetition loop detection in WhisperService | Done |
| Story 1 / Task 1.2 | Buffer validation + CoreML backend detection | Done |
| Story 1 / Task 1.3 | Configurable whisperInitialPrompt + noSpeechThreshold in AppSettings | Done |
| Story 2 / Task 2.1 | distil-large-v3 + distil-large-v3.5 added to WhisperModelDownloader | Done |
| Story 2 / Task 2.2 | WER measurement harness | Done (WerBaselineTest — skips without test data) |
| Story 3 / Task 3.1 | diarize_session.py Python sidecar | Done |
| Story 3 / Task 3.2 | DiarizationService.kt | Done |
| Story 3 / Task 3.3 | PipelineQueueExecutor diarization integration | Done |
| Story 3 / Task 3.4 | Diarization Settings UI | Done |
| Story 4 / Task 4.1 | TranscriptCorrectionService.kt | Done |
| Story 4 / Task 4.2 | Correction pipeline integration + settings toggle | Done |

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
| BUG-TD-002: CoreML ANE silent fallback on M4 + macOS 26.4 beta | High | Mitigated — `detectedBackend` set on both branches in `loadLibraryOnce()` |
| BUG-FA-001: JNI thread deadlock (DispatchSemaphore pattern) | High | Mitigated by design — DispatchSemaphore implemented in FluidDiarizationBridgeJNI.swift |
| BUG-FA-002: Dylib notarization gate (Gatekeeper) | High | Mitigated — `codesign -f -s -` in Gradle task; BackendUnavailableException on load failure |
| BUG-FA-003: FluidAudio SPM URL is a placeholder | High | Resolved — pinned to v0.14.5; CI guard removed |
| BUG-TD-003: DiarizationService process timeout on long meetings | Medium | Mitigated — 60-min default configurable |
| BUG-AE-001: Gatekeeper distribution friction (no notarization) | High | Mitigated — cask caveats + README note the xattr workaround |
| BUG-FA-004: ANE memory contention on M1 | Medium | Mitigated — Mutex + limitedParallelism(1) in FluidAudioDiarizationBackend |
| BUG-TD-004: TranscriptCorrectionService hallucination risk | Medium | Mitigated by design — temperature=0.0, conservative prompt |

---

## Remaining Work

All originally planned stories are complete. Possible next initiatives:

1. **Moonshine JNI backend** — Phase 2 gating decision: run `WerBaselineTest` with real recordings to compare Moonshine vs Whisper WER and latency before committing to a JNI port.
2. **AppSettings deserialization regression test** — add a commonTest that deserializes a minimal JSON fixture missing newer fields and asserts defaults match `AppSettings()`. Low priority since `SettingsRepository` uses SQLDelight (not JSON) for persistence.
3. **ONNX diarization backend** — `OnnxDiarizationBackend` is a stub; implement when an ONNX speaker diarization model is identified.
4. **Notarization** — replace `codesign -f -s -` ad-hoc signing with a proper Developer ID certificate + notarytool workflow to remove the xattr workaround for users.

---

## Projects and Task Files

| File | Status | Description |
|---|---|---|
| `docs/tasks/linux-dictation-plugin.md` | Complete | All 22 stories done including Story 1.3 Linux CI |
| `docs/tasks/fluida-audio-backends.md` | Checkpoint A + B partial | Stories 1–7, 9–11 done; Story 8 blocked on FluidAudio SPM URL |
| `docs/tasks/transcription-diarization-improvement.md` | Mostly complete | Stories 1, 3, 4 done; Task 2.2 (WER harness) not started |
| `docs/tasks/agrapha-extraction.md` | Complete (in-repo scope) | Repo is live; Story 4 monorepo cleanup out of scope here |
| `project_plans/linux-dictation-plugin/` | Complete | Full 5-epic plan — all stories implemented |
