# Feature Plan: FluidAudio Backends — Phase 1 Diarization JNI Bridge

Created: 2026-04-16
Spec: `project_plans/agrapha-fluid-audio/`
ADRs: `project_plans/agrapha-fluid-audio/decisions/`

---

## Epic Overview

### User Value

Users currently need Python installed, a HuggingFace account, and a working `pyannote` environment to get speaker diarization. This fails silently on new Macs, breaks after OS upgrades, and requires a 60-minute subprocess timeout with no progress feedback. After Phase 1, diarization runs entirely on-device via CoreML on Apple Neural Engine — zero Python, zero token, 122x real-time speed. A new backend selector in Settings lets users switch between FluidAudio (macOS 14+) and the legacy Python path without re-configuring anything.

### Success Metrics

- Diarization completes on a fresh macOS 14 machine with no Python installed
- `PipelineQueueExecutor` diarization block is a single `backend.diarize()` call (no subprocess logic inline)
- `PythonDiarizationBackend` produces byte-for-byte identical output to the pre-refactor `DiarizationService` on the same audio file
- Settings UI shows backend selector dropdown replacing the HuggingFace token field when `diarizationBackend != "python"`
- JNI bridge loads without crash on macOS 14; returns `BackendUnavailableException` with message on macOS 12–13

### Scope

**In scope (Phase 1):**
- `DiarizationBackend` interface + exception hierarchy (commonMain)
- `DiarizationSegment` value object promotion to commonMain
- `PythonDiarizationBackend` wrapping existing `DiarizationService` (zero behavioral change)
- `FluidAudioDiarizationBackend` with dylib loading + full JNI call chain
- `OnnxDiarizationBackend` stub (throws `NotImplementedError`, documents the KMP fallback path)
- `AudioAiBackendFactory` mirroring `LlmProviderFactory`
- `AppSettings` new field: `diarizationBackend: String = "fluida"`
- `PipelineQueueExecutor` refactor: inline diarization block replaced with factory call
- Swift SPM package `FluidDiarizationBridge` with `@_cdecl` JNI functions + DispatchSemaphore bridging
- Gradle `buildFluidDiarizationBridge` Exec task
- Settings UI: backend selector dropdown, conditional HuggingFace token field
- Unit tests: interface contract, factory selection, Python backend wrapper parity

**Out of scope (Phase 1) — planned phases:**
- Phase 1.5: FluidAudio VAD bridge (`VoiceActivityDetector` interface + `FluidAudioVadBackend`) — silence filtering before Whisper
- Phase 2: Streaming diarization (`SortformerDiarizationBackend`) + streaming ASR hooks
- Phase 3: Parakeet TDT ASR backend (`FluidAudioParakeetBackend`) as user-selectable Whisper.cpp alternative

### Constraints

- macOS 14+ required by FluidAudio; factory must fall back to `PythonDiarizationBackend` on macOS 12–13
- JNI bridge dylib pattern must match `AudioCaptureBridgeJNI.dylib` (proven build + load path)
- `huggingFaceToken` field kept in `AppSettings`; deprecated but not removed (used by `PythonDiarizationBackend`)
- All processing on-device; no cloud calls
- `DiarizationService.applyDiarization()` must remain callable regardless of which backend ran diarization

---

## Architecture Decisions

| ADR | File | Decision |
|---|---|---|
| ADR-001 | `decisions/ADR-001-audio-backend-strategy.md` | Strategy pattern + DI mirroring LlmProvider; interfaces in commonMain, implementations in desktopMain |
| ADR-002 | `decisions/ADR-002-jni-async-bridging.md` | DispatchSemaphore pattern for JNI-to-Swift-async bridging |
| ADR-003 | `decisions/ADR-003-vad-pipeline-placement.md` | VAD deferred to Phase 1.5; interface stub only in Phase 1 |

---

## Story Breakdown

### Story 1: Domain Interface Layer [commonMain]

**User value:** Establishes the stable contract that all current and future audio AI backends implement. PipelineQueueExecutor and all callers bind to this interface forever — backend swaps require zero caller changes.

**Acceptance criteria:**
- `DiarizationBackend` interface compiles in commonMain with no platform-specific imports
- `DiarizationSegment` is a data class in commonMain, replacing the service-internal one
- Exception types (`BackendUnavailableException`, `ModelDownloadRequiredException`, `DiarizationFailedException`) are defined in commonMain
- `areModelsAvailable()` and `downloadModels()` are on the interface (required for model-download UX)
- Interface is structurally identical to `LlmProvider` — same method naming convention, same `isAvailable()` contract

**INVEST validation:** Independent (no other story required), Negotiable (exception names adjustable), Valuable (unblocks everything), Estimable (2h max), Small (4 files), Testable (interface compilability + mock injection test)

---

### Story 2: PythonDiarizationBackend — Zero-Risk Wrapper [desktopMain]

**User value:** Phase 1 ships with no behavioral regression. Users on macOS 12–13 or those with Python configured continue working identically. The new factory architecture is live without touching the diarization model.

**Acceptance criteria:**
- `PythonDiarizationBackend.diarize()` delegates to `DiarizationService.diarize()` and converts `DiarizationResult.Success` to `List<DiarizationSegment>` with segment field parity
- `DiarizationResult.Unavailable` maps to `BackendUnavailableException`
- `DiarizationResult.Failed` maps to `DiarizationFailedException`
- `isAvailable()` returns `true` only when Python executable is resolvable
- `areModelsAvailable()` delegates to existing pyannote model check (or returns `true` if check is not implemented — note this in code)
- Existing diarization integration test (if present) passes unchanged

**INVEST validation:** Independent of Story 3/4/5, Valuable (regression safety), Small (1 file, ~80 lines), Testable (parity test against raw `DiarizationService` output)

---

### Story 3: AudioAiBackendFactory + AppSettings Field [desktopMain + commonMain]

**User value:** The single integration point where backend selection logic lives. Settings field lets users persist their backend preference across sessions.

**Acceptance criteria:**
- `AppSettings` has `diarizationBackend: String = "fluida"` with `@Serializable` (backward-compatible default)
- `AudioAiBackendFactory.createDiarizationBackend(settings)` returns correct implementation for `"fluida"`, `"python"`, `"onnx"` string values
- Factory uses `runCatching { FluidAudioDiarizationBackend() }.getOrNull()` to handle dylib load failure; falls back to `PythonDiarizationBackend` with a log warning
- Factory returns `PythonDiarizationBackend` if `Build.VERSION < macOS 14` (macOS version check utility)
- Unknown `diarizationBackend` string value logs warning and returns `PythonDiarizationBackend` (safe default)
- `AudioAiBackendFactory` is an `object` mirroring `LlmProviderFactory`

**INVEST validation:** Depends on Story 1 + 2 (blocked until interface and wrapper exist), Valuable (wires everything together), Small (2 files — factory + settings field), Testable (factory unit test for all three string values + fallback)

---

### Story 4: PipelineQueueExecutor Refactor [desktopMain]

**User value:** Removes 35 lines of fragile inline subprocess logic from the pipeline orchestrator, replacing with a single `backend.diarize()` call. Future backend changes require zero changes in this file.

**Acceptance criteria:**
- `diarizationRunnerFull` lambda body is replaced with: factory call, `backend.diarize()`, `diarizationService.applyDiarization()` (reuse existing apply logic)
- `settings.huggingFaceToken.isNotBlank()` guard is replaced with `settings.diarizationEnabled`
- Exception types from Story 1 are caught and mapped to log messages (not propagated to crash the pipeline)
- Model-download required path (`ModelDownloadRequiredException`) sets recording state to `NeedingModelDownload` (existing state machine entry point)
- `DiarizationService` is no longer directly constructed in this file
- All existing pipeline integration tests pass

**INVEST validation:** Depends on Story 3, Valuable (the actual behavioral improvement), Small (1 file, ~15 line diff), Testable (inject mock `DiarizationBackend`, verify `applyDiarization` called with correct segments)

---

### Story 5: FluidAudioDiarizationBackend Skeleton [desktopMain]

**User value:** The factory can instantiate `FluidAudioDiarizationBackend` and call `isAvailable()` + `areModelsAvailable()` without the JNI bridge compiled. Enables frontend and factory development to proceed in parallel with bridge implementation.

**Acceptance criteria:**
- `FluidAudioDiarizationBackend` loads dylib from `ResourceLoader.getFluidDiarizationBridgePath()` in constructor; throws `BackendUnavailableException` if path not found
- `isAvailable()` checks macOS version >= 14 via `NSProcessInfo` (exposed through existing platform utilities or new `PlatformInfo` helper)
- `areModelsAvailable()` calls JNI stub `fluidDiarization_areModelsAvailable()` which returns `false` until bridge is compiled
- `diarize()` calls JNI stub `fluidDiarization_diarize()` which throws `NotImplementedError` until bridge is compiled
- `downloadModels()` calls JNI stub that logs "bridge not built" and returns
- Class compiles and factory can instantiate it — bridge absence is a runtime `BackendUnavailableException`, not a compile error

**INVEST validation:** Partially depends on Story 1 (interface), independent of bridge story (Stories 6–7), Small (1 file + JNI stubs), Testable (factory instantiates, `isAvailable()` returns false in test environment)

---

### Story 6: Swift SPM Package — FluidDiarizationBridge [native]

**User value:** The actual CoreML diarization runs on-device. This is the functional heart of Phase 1 — the Swift code that calls `OfflineDiarizerManager` and exposes results over JNI.

**Acceptance criteria:**
- `composeApp/native/FluidDiarizationBridge/` is a valid Swift Package with `Package.swift` declaring FluidAudio as dependency
- `FluidDiarizationBridgeJNI.swift` exports four `@_cdecl` functions: `fluidDiarization_areModelsAvailable`, `fluidDiarization_downloadModels`, `fluidDiarization_diarize`, `fluidDiarization_freeResult`
- `fluidDiarization_diarize` uses DispatchSemaphore to block the JNI thread while `OfflineDiarizerManager.process()` runs asynchronously (mirrors `AudioCaptureBridgeJNI.m` pattern)
- `OfflineDiarizerManager` is instantiated once at bridge init; not re-created per-call (CoreML model load is expensive)
- Result is serialized as a null-terminated C string of JSON (`[{"start":0.0,"end":2.3,"speaker":"SPEAKER_0"},...]`) — simple, no custom marshaling struct required
- Error path: any `NSError` from `process()` produces a JSON error object `{"error":"<message>"}` — caller checks for `error` key
- `fluidDiarization_freeResult` calls `free()` on the C string (caller must always call this to prevent leak)
- Bridge compiles with `swift build -c release` in isolation before Gradle integration

**INVEST validation:** Independent of Kotlin stories once JNI stub names are agreed (from Story 5), Valuable (the entire Phase 1 goal), Estimable (3–4h core bridge, 1h model download path), Testable (Swift unit test calling bridge directly with a 10-second WAV fixture)

---

### Story 7: Gradle Build Integration [build system]

**User value:** `./gradlew run` builds the bridge dylib automatically. Developers do not need to manually run `swift build`. CI produces a correctly signed dylib.

**Acceptance criteria:**
- `buildFluidDiarizationBridge` is a Gradle `Exec` task in `composeApp/build.gradle.kts` that runs `swift build -c release` in `native/FluidDiarizationBridge/`
- Output dylib is copied to `composeApp/src/desktopMain/resources/native/` so `ResourceLoader` can find it
- `composeApp:run` depends on `buildFluidDiarizationBridge`
- Post-build step runs `codesign -f -s - <dylib>` (ad-hoc sign for local development; notarization is CI-only)
- `buildFluidDiarizationBridge` is skipped if dylib is already up-to-date (file modification time check via Gradle incremental inputs)
- Build task documented in project README with note about notarytool requirement for distribution builds

**INVEST validation:** Depends on Story 6 (bridge must exist to build), Small (1 Gradle file modification), Testable (clean build produces dylib at expected path)

---

### Story 8: FluidAudioDiarizationBackend — Full Implementation [desktopMain]

**User value:** Replaces the stub with the full JNI call chain. Diarization now runs on Apple Neural Engine. Users with Python issues can switch to `"fluida"` backend and get working speaker labels.

**Acceptance criteria:**
- `FluidAudioDiarizationBackend.diarize()` calls `fluidDiarization_diarize()` via JNI, parses JSON result, returns `List<DiarizationSegment>`
- JSON `{"error":"..."}` response is mapped to `DiarizationFailedException`
- `downloadModels()` calls `fluidDiarization_downloadModels()` and invokes `progressCallback` based on periodic status polls (or best-effort if FluidAudio API does not expose granular progress)
- `areModelsAvailable()` calls `fluidDiarization_areModelsAvailable()` and returns the boolean result
- ANE concurrency guard: `diarize()` acquires a `Mutex` before the JNI call; only one diarization runs at a time (addresses ANE memory pressure on M1)
- `FluidAudioDiarizationBackend` is restricted to `Dispatchers.Default.limitedParallelism(1)` for all JNI calls
- Manual integration test: 5-minute WAV with two speakers produces >= 2 distinct speaker labels

**INVEST validation:** Depends on Stories 5, 6, 7, Valuable (functional goal), Estimable (2–3h after bridge works), Testable (integration test with known audio fixture)

---

### Story 9: OnnxDiarizationBackend Stub [desktopMain]

**User value:** Declares the portability path for Linux/Windows contributors. Architecture is documented in code, not just in an ADR file.

**Acceptance criteria:**
- `OnnxDiarizationBackend` class exists in `desktopMain/kotlin/data/audio/`
- `isAvailable()` returns `false`
- `areModelsAvailable()` returns `false`
- `diarize()` throws `NotImplementedError("OnnxDiarizationBackend not yet implemented. See ADR-001 for KMP fallback roadmap.")`
- Factory can instantiate it when `diarizationBackend == "onnx"` without crash
- Class has a KDoc comment linking to ADR-001 and the ONNX Runtime Java dependency that would be needed

**INVEST validation:** Independent (no other story blocks it), Small (1 file, ~30 lines), Testable (instantiation test)

---

### Story 10: Settings UI — Backend Selector [desktopMain]

**User value:** Users can switch between FluidAudio and Python backends through the UI without editing config files. HuggingFace token field is hidden for non-Python backends (reduces visual noise for the majority case).

**Acceptance criteria:**
- Settings screen shows a "Diarization Backend" dropdown with options: "FluidAudio (macOS 14+)", "Python / pyannote", "ONNX (not yet available)"
- "ONNX" option is shown but visually disabled (grayed out, tooltip: "Not yet implemented")
- HuggingFace token input field is visible only when `diarizationBackend == "python"`
- Selecting "FluidAudio" when macOS version < 14 shows an inline warning: "Requires macOS 14 or later. FluidAudio will not be available."
- `areModelsAvailable()` result drives a "Download Models" button visible when backend is FluidAudio and models are not present
- Model download shows an indeterminate progress indicator (granular progress is best-effort per Story 8 acceptance criteria)
- Settings changes persist to `AppSettings` via existing settings save flow

**INVEST validation:** Depends on Story 3 (AppSettings field), Stories 5/8 (backend available), Valuable (user-facing), Testable (UI state tests for field visibility conditions)

---

### Story 11: Tests [desktopTest]

**User value:** Regression safety for the refactor. Any future backend addition or pipeline change is caught before it reaches users.

**Acceptance criteria:**
- `DiarizationBackendTest`: mock implementation verifies interface contract (all methods callable, correct return types)
- `AudioAiBackendFactoryTest`: verifies factory returns correct type for `"fluida"`, `"python"`, `"onnx"` values; verifies fallback to `PythonDiarizationBackend` on dylib load failure
- `PythonDiarizationBackendTest`: constructs `PythonDiarizationBackend` with a mock `DiarizationService`; verifies `Success` path maps correctly; verifies `Unavailable` maps to `BackendUnavailableException`; verifies `Failed` maps to `DiarizationFailedException`
- `PipelineQueueExecutorDiarizationTest`: injects mock `DiarizationBackend` via factory; verifies `applyDiarization` called with segments from repository; verifies `ModelDownloadRequiredException` triggers `NeedingModelDownload` state
- All tests run without Python installed (no subprocess spawning)

**INVEST validation:** Depends on Stories 1–4, 9 (all Kotlin stories), Independent of bridge (mock avoids JNI), Small (4 test files), Testable (by definition)

---

## Known Issues — Proactive Bug Identification

### JNI Thread Deadlock — Severity: High

**Description:** `OfflineDiarizerManager.process()` is `async` in Swift. If called directly from the JNI thread without the DispatchSemaphore pattern, the JNI thread returns immediately with an uninitialized result.

**Mitigation:**
- Story 6 acceptance criteria mandates DispatchSemaphore; reviewed against `AudioCaptureBridgeJNI.m` pattern before merge
- The Semaphore is created on the stack of the `@_cdecl` function — it cannot be captured before initialization
- Integration test (Story 8) exercises the full JNI-to-Swift-to-CoreML path; deadlock would manifest as a hung test, not a crash

**Files affected:** `FluidDiarizationBridgeJNI.swift`, `FluidAudioDiarizationBackend.kt`

---

### Dylib Notarization Gate — Severity: High

**Description:** macOS Gatekeeper blocks unsigned dylibs loaded at runtime from `Resources/`. Ad-hoc signing works for local development but unsigned builds crash for any user who received the app via download (not Xcode run).

**Mitigation:**
- Story 7 acceptance criteria includes `codesign -f -s -` post-build for local development
- Notarytool step documented as required in CI/release pipeline (Story 7 README note)
- Load failure in `FluidAudioDiarizationBackend` constructor produces `BackendUnavailableException` with message "dylib blocked by Gatekeeper" — not a silent crash

**Files affected:** `composeApp/build.gradle.kts`, `FluidAudioDiarizationBackend.kt` (load error handling)

---

### ANE Memory Contention on M1 — Severity: Medium

**Description:** Running two CoreML models concurrently on Apple Neural Engine (e.g., Whisper.cpp + diarization) can exhaust ANE memory budget, causing one or both models to fall back to CPU with a silent performance regression or OOM termination.

**Mitigation:**
- Story 8 acceptance criteria: `diarize()` acquires a `Mutex` before JNI call, limiting ANE diarization to one concurrent execution
- Diarization runs post-recording (not concurrent with Whisper in Phase 1 pipeline) — timing already separates the two
- `limitedParallelism(1)` on the dispatcher prevents coroutine scheduling from bypassing the mutex

**Files affected:** `FluidAudioDiarizationBackend.kt`

---

### macOS Version Check Missing — Severity: Medium

**Description:** If `AudioAiBackendFactory` does not check macOS version before instantiating `FluidAudioDiarizationBackend`, users on macOS 12–13 get a dylib load error with a cryptic message instead of a clean fallback.

**Mitigation:**
- Story 3 acceptance criteria includes explicit macOS version check in factory before attempting `FluidAudioDiarizationBackend()` construction
- `PlatformInfo.macOSMajorVersion()` utility needed — either use existing one or create a 5-line helper
- Factory test exercises the `< macOS 14` branch with a mocked `PlatformInfo`

**Files affected:** `AudioAiBackendFactory.kt`, `PlatformInfo.kt` (new utility)

---

### Model Download First-Run UX — Severity: Medium

**Description:** `OfflineDiarizerManager.prepareModels()` downloads CoreML models on first run. If `diarize()` is called before models are downloaded, it will either fail opaquely or block for minutes with no UI feedback.

**Mitigation:**
- `areModelsAvailable()` called by factory / UI before enabling FluidAudio backend (Story 10 acceptance criteria)
- `ModelDownloadRequiredException` propagated from `diarize()` if `areModelsAvailable()` returns `false` and `prepareModels()` has not been called
- `PipelineQueueExecutor` catches `ModelDownloadRequiredException` and sets `RecordingState.NeedingModelDownload` (Story 4 acceptance criteria)

**Files affected:** `FluidAudioDiarizationBackend.kt`, `PipelineQueueExecutor.kt`, `RecordingState.kt`

---

### JSON Result Leak — Severity: Low

**Description:** `fluidDiarization_diarize()` returns a `char*` allocated by `strdup()` on the Swift side. If the Kotlin caller does not call `fluidDiarization_freeResult()` after parsing, the memory leaks permanently (no GC on C strings).

**Mitigation:**
- Story 6 acceptance criteria defines `fluidDiarization_freeResult()` as a required export
- Story 8 implementation of `FluidAudioDiarizationBackend.diarize()` wraps the JNI call in `try/finally` to guarantee `freeResult` is called even on parse failure
- Code review checklist item: "JNI result string is freed in finally block"

**Files affected:** `FluidDiarizationBridgeJNI.swift`, `FluidAudioDiarizationBackend.kt`

---

### AppSettings Deserialization Break — Severity: Low

**Description:** Existing serialized `AppSettings` JSON files (on-disk user settings) do not contain `diarizationBackend`. If deserialization is strict, startup crashes for all existing users.

**Mitigation:**
- `diarizationBackend: String = "fluida"` — the Kotlin `@Serializable` default value mechanism handles missing fields gracefully (kotlinx.serialization uses defaults for absent JSON keys)
- Verification: unit test deserializes a `AppSettings` JSON fixture that predates the new field; asserts `diarizationBackend == "fluida"`

**Files affected:** `AppSettings.kt`

---

## Dependency Visualization

```
Story 1: Domain Interface Layer (commonMain)
  |
  +-- Story 2: PythonDiarizationBackend
  |     |
  |     +-- Story 3: AudioAiBackendFactory + AppSettings
  |           |
  |           +-- Story 4: PipelineQueueExecutor Refactor
  |           |
  |           +-- Story 10: Settings UI
  |
  +-- Story 5: FluidAudioDiarizationBackend Skeleton
        |
        +-- Story 6: Swift SPM Package (parallel track)
        |     |
        |     +-- Story 7: Gradle Build Integration
        |           |
        |           +-- Story 8: FluidAudioDiarizationBackend Full Implementation
        |
        +-- Story 9: OnnxDiarizationBackend Stub (independent)

Story 11: Tests
  depends on: Stories 1, 2, 3, 4, 9
  independent of: Stories 6, 7, 8 (uses mocks)
```

**Critical path:** 1 → 2 → 3 → 4 (Kotlin refactor, ~6–8h)
**Parallel track:** 1 → 5 → 6 → 7 → 8 (bridge, ~8–10h)
**Tests and UI:** can begin after Story 3 (Settings field) + Story 4 (executor)

---

## Integration Checkpoints

### Checkpoint A: Architecture Live, No Behavioral Change
Stories 1, 2, 3, 4 complete. `PythonDiarizationBackend` is wired through the factory. Existing Python diarization produces identical output via the new path. All Story 11 Kotlin tests pass. This checkpoint is a releasable state — architecture is in place, no regression, bridge work continues independently.

**Verification:** Run existing integration test against a known WAV + HuggingFace token. Output segments must match pre-refactor output byte-for-byte.

### Checkpoint B: Bridge Compiles and Loads
Stories 5, 6, 7 complete. `FluidAudioDiarizationBackend` loads the dylib, calls `isAvailable()`, returns a result. `areModelsAvailable()` returns `false` (models not downloaded yet). No crash on macOS 14.

**Verification:** `AudioAiBackendFactoryTest` instantiates `FluidAudioDiarizationBackend`, calls `isAvailable()` — returns `true` on macOS 14, `BackendUnavailableException` on macOS 12/13.

### Checkpoint C: End-to-End Diarization via FluidAudio
Stories 8, 10 complete. User sets `diarizationBackend = "fluida"` in Settings. Records a 5-minute meeting with two participants. Post-processing assigns `SPEAKER_0` / `SPEAKER_1` labels to transcript segments. Export to Logseq includes speaker attribution.

**Verification:** Manual test with known two-speaker WAV fixture. Confirm >= 2 distinct speaker labels. Confirm `applyDiarization` is called with segments from repository.

---

## Context Preparation Guide Per Task Group

### Kotlin Stories (1–5, 9–11)
Load into context before starting:
- `composeApp/src/commonMain/kotlin/domain/LlmProvider.kt` (interface to mirror)
- `composeApp/src/desktopMain/kotlin/data/LlmProviderFactory.kt` (factory to mirror)
- `composeApp/src/desktopMain/kotlin/data/audio/DiarizationService.kt` (wrapper target)
- `composeApp/src/commonMain/kotlin/domain/AppSettings.kt` (field to add)
- `composeApp/src/desktopMain/kotlin/pipeline/PipelineQueueExecutor.kt` lines 160–210 (block to replace)

### Swift Bridge Story (6)
Load into context before starting:
- `composeApp/native/AudioCaptureBridge/` — existing bridge package for build pattern reference
- FluidAudio `OfflineDiarizerManager` API (from FluidAudio Swift package documentation or source)
- `AudioCaptureBridgeJNI.m` — DispatchSemaphore pattern reference

### Gradle Story (7)
Load into context before starting:
- `composeApp/build.gradle.kts` — existing build file (find existing `buildAudioCaptureBridge` or equivalent Exec task)
- Story 6 output path (`native/FluidDiarizationBridge/.build/release/`)

### Settings UI Story (10)
Load into context before starting:
- Existing Settings screen composable (find via `SettingsScreen.kt` or equivalent)
- `AppSettings.kt` with new `diarizationBackend` field (after Story 3)
- `RecordingState.kt` — to confirm `NeedingModelDownload` state exists
