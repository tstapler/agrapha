# Implementation Plan: Linux Support + Dictation Plugin API

**Project:** linux-dictation-plugin
**Date:** 2026-05-09
**Author:** Tyler Stapler
**Status:** Ready for implementation

---

## Overview

This plan delivers Linux parity for Agrapha, a plugin API via `java.util.ServiceLoader`, and a built-in dictation plugin with three modes. It is organized into 5 epics and 22 stories. All macOS code paths are preserved without modification; Linux code is additive.

---

## Dependency Graph

```
Epic 1: Linux Runtime Baseline
  1.1 PlatformInfo ──────────────────────────────────────────────┐
  1.2 Verify Linux mic/whisper/logseq ──────────────────────────►│
  1.3 Gradle Linux CI ──────────────────────────────────────────►│
                                                                  │
Epic 2: SystemAudioBackend                                        │
  2.1 SystemAudioBackend interface ◄────────────────── 1.1        │
  2.2 ScreenCaptureBackend ◄──────────────────────── 2.1         │
  2.3 RecordingSessionManager refactor ◄───────────── 2.2, 2.5   │
  2.4 PipeWireCaptureBridge (C/JNI) ◄─────────────── 1.1         │
  2.5 PipeWireCaptureBackend (Kotlin) ◄────────────── 2.1, 2.4   │
  2.6 SystemAudioBackendFactory ◄──────────────────── 2.2, 2.5, 1.1
  2.7 Gradle PipeWire build task ◄────────────────── 2.4, 1.3    │
                                                                  │
Epic 3: Plugin loading infrastructure                             │
  3.1 DictationMode enum ◄──────────────────────────────────────►│
  3.2 SpeechOutputPlugin interface ◄──────────────── 3.1         │
  3.3 PluginLoader ◄──────────────────────────────── 3.2         │
  3.4 AppSettings addition ◄──────────────────────── 3.2         │
  3.5 Settings UI ◄──────────────────────────────── 3.3, 3.4     │
                                                                  │
Epic 4: TextInjector abstraction                                  │
  4.1 TextInjector interface ◄──────────────────────────────────►│
  4.2 YdotoolTextInjector ◄───────────────────────── 4.1         │
  4.3 XdotoolTextInjector ◄───────────────────────── 4.1         │
  4.4 AutoDetectTextInjector ◄────────────────────── 4.2, 4.3    │
                                                                  │
Epic 5: Dictation plugin (built-in SPI)                           │
  5.1 DictationPlugin shell ◄────────────────────── 3.2, 4.4    │
  5.2 PUSH_TO_TALK mode ◄────────────────────────── 5.1, 1.1     │
  5.3 FILE_TRANSCRIPTION mode ◄──────────────────── 5.1          │
  5.4 LIVE_CAPTIONS mode ◄───────────────────────── 5.1          │
  5.5 ServiceLoader registration ◄───────────────── 5.1, 3.3     │
```

### Critical Path

```
1.1 → 2.1 → 2.4 → 2.5 → 2.3   (system audio for Linux — longest chain in Epic 2)
1.1 → 3.1 → 3.2 → 3.3 → 5.1 → 5.2   (dictation plugin — longest overall chain)
```

The critical path is 6 stories deep. Stories 1.1 and 3.1 are the only two with zero dependencies; both should be started on day one in parallel.

---

## Epic 1: Linux Runtime Baseline

**Goal:** Establish that Agrapha's existing pipeline runs on Linux with no code changes, and add CI coverage to prove it.

---

### Story 1.1 — PlatformInfo utility

**What:** Create a `PlatformInfo` singleton in `desktopMain` exposing `isLinux()`, `isMac()`, `isWayland()`, `isX11()`.

**Why:** All subsequent platform-conditional code in Epics 2–5 reads from one source of truth instead of scattering `System.getProperty("os.name")` calls throughout the codebase.

**Acceptance criteria:**
- `PlatformInfo.kt` in `composeApp/src/desktopMain/kotlin/platform/` with an `object Platform` exposing `Os` enum and boolean helpers.
- `isLinux()` returns `true` when `os.name` starts with `"linux"` (case-insensitive).
- `isMac()` returns `true` when `os.name` starts with `"mac"`.
- `isWayland()` returns `true` when `WAYLAND_DISPLAY` env var is non-null.
- Unit tests cover all four cases using `System.getProperty` overrides or a testable constructor injection of OS name string.

**INVEST validation:**
- Independent: no dependencies; can be written before anything else.
- Negotiable: the exact API (object vs class, enum shape) is open to review.
- Valuable: eliminates scattered `os.name` string comparisons across 10+ future call sites.
- Estimable: 1–2 hours including tests.
- Small: single file, ~50 lines.
- Testable: pure property-read logic; test by injecting a fake os.name string.

**Estimated effort:** S

**Dependencies:** none

---

### Story 1.2 — Verify Linux mic + Whisper + Logseq export

**What:** Run Agrapha on a Linux machine (or CI runner) and confirm that `MicCaptureService`, `WhisperService`, and Logseq export all work without code changes; document any discovered issues.

**Why:** Prevents shipping Epic 2 work against an untested baseline; catches GLIBC/AVX2 whisper-jni issues early before they block the whole feature.

**Acceptance criteria:**
- `./gradlew :composeApp:run` starts the application on Ubuntu 22.04 (or equivalent CI runner).
- A short mic recording completes and produces a non-empty transcript via `WhisperService`.
- Logseq export writes a journal entry to a temp directory.
- Any discovered issues are logged as GitHub issues (not silently worked around).
- A short prose note is added to this plan's Known Issues section (below) if gaps are found.
- `whisper-jni` CPU flag check (AVX2/FMA/F16C) is verified to not crash on the CI runner CPU.

**INVEST validation:**
- Independent: does not require Epics 2–5; it validates the existing code.
- Negotiable: can be done manually first, then automated in CI (Story 1.3 adds automation).
- Valuable: de-risks the entire project; avoids building on a broken baseline.
- Estimable: 2–4 hours; mostly environment setup and observation.
- Small: no new code unless issues are found; mostly investigation.
- Testable: pass/fail is clear from app launch + a short recording run.

**Estimated effort:** S

**Dependencies:** none

---

### Story 1.3 — Gradle Linux CI job

**What:** Add a GitHub Actions job in `.github/workflows/build.yml` that runs `./gradlew :composeApp:run` (headless) and the full test suite on an `ubuntu-latest` runner.

**Why:** Prevents Linux regressions; ensures every PR is validated on Linux as well as macOS.

**Acceptance criteria:**
- New job `build-linux` added to `.github/workflows/build.yml` with `runs-on: ubuntu-latest`.
- Job installs `libpipewire-0.3-dev` and `libspa-0.2-dev` (needed by Epic 2.7 later); also installs `ydotool` for integration test fixtures.
- `./gradlew :composeApp:desktopTest` passes on the runner.
- Job is non-blocking for macOS job; both run in parallel.
- `DISPLAY` / XWayland setup is handled via `xvfb-run` so AWT-dependent tests do not fail headlessly.

**INVEST validation:**
- Independent: CI job can be written before PipeWire code; uses the existing Gradle tasks.
- Negotiable: CI provider and specific runner image are adjustable.
- Valuable: enforces Linux compatibility on every commit; reduces manual QA burden.
- Estimable: 2–3 hours.
- Small: CI yaml changes only; no Kotlin changes.
- Testable: job either passes or fails visibly in GitHub Actions.

**Estimated effort:** S

**Dependencies:** 1.2 (need to know the baseline works before adding CI enforcement)

---

## Epic 2: SystemAudioBackend Interface + Platform Injection

**Goal:** Introduce a `SystemAudioBackend` abstraction so `RecordingSessionManager` dispatches to either the existing macOS `ScreenCaptureJniBridge` or a new Linux `PipeWireCaptureBridge` without `if (isMac)` scattered in business logic.

---

### Story 2.1 — SystemAudioBackend interface

**What:** Define the `SystemAudioBackend` interface in `desktopMain` with five methods mirroring the existing JNI function signatures.

**Why:** Decouples `RecordingSessionManager` from `ScreenCaptureJniBridge`; enables both mock testing and Linux backend injection without touching macOS code.

**Acceptance criteria:**
- `SystemAudioBackend.kt` in `composeApp/src/desktopMain/kotlin/audio/` with the interface:
  ```kotlin
  interface SystemAudioBackend {
      fun checkPermission(): Boolean
      fun requestPermission(): Boolean
      fun startCapture(sampleRate: Int): Boolean
      fun stopCapture()
      fun readBuffer(buffer: FloatArray): Int
      fun isAvailable(): Boolean
  }
  ```
- A `SilentAudioBackend` (no-op implementation) is also added: `checkPermission`/`requestPermission` return `true`; `startCapture` returns `false`; `readBuffer` returns 0.
- Interface and silent backend have unit tests confirming `SilentAudioBackend` returns safe defaults.
- No changes to any existing file at this stage.

**INVEST validation:**
- Independent: pure new interface; no existing code changes.
- Negotiable: `isAvailable()` placement (interface vs factory) is negotiable.
- Valuable: enables both platform dispatch (Stories 2.2–2.6) and mock testing.
- Estimable: 1 hour.
- Small: ~60 lines across two files.
- Testable: `SilentAudioBackend` has immediate unit tests.

**Estimated effort:** S

**Dependencies:** 1.1

---

### Story 2.2 — ScreenCaptureBackend (macOS adapter)

**What:** Create `ScreenCaptureBackend` wrapping `ScreenCaptureJniBridge` behind `SystemAudioBackend`; `ScreenCaptureJniBridge` itself is not modified.

**Why:** Allows `RecordingSessionManager` to depend on the interface, not the concrete macOS class, with zero change to macOS runtime behavior.

**Acceptance criteria:**
- `ScreenCaptureBackend.kt` in `desktopMain/kotlin/audio/` implements `SystemAudioBackend`.
- All five methods delegate directly to the corresponding `ScreenCaptureJniBridge.nativeXxx` functions.
- `isAvailable()` returns `true` only on macOS (`PlatformInfo.isMac()`).
- `ScreenCaptureJniBridge.kt` is not modified.
- Existing macOS tests still pass.

**INVEST validation:**
- Independent: wraps existing code; no logic changes.
- Negotiable: lazy vs eager library loading is a detail.
- Valuable: completes the adapter pattern; makes Story 2.3 safe to write.
- Estimable: 1 hour.
- Small: ~40 lines, mostly delegation.
- Testable: mock `ScreenCaptureJniBridge` JNI calls via Mockk; test delegation.

**Estimated effort:** S

**Dependencies:** 2.1

---

### Story 2.3 — Refactor RecordingSessionManager to accept SystemAudioBackend

**What:** Change `RecordingSessionManager` to receive a `SystemAudioBackend` via constructor injection instead of calling `ScreenCaptureJniBridge` directly.

**Why:** `RecordingSessionManager` currently hard-codes macOS JNI calls at lines 102–114; this change makes it platform-neutral and testable without JNI.

**Acceptance criteria:**
- `RecordingSessionManager` constructor gains parameter `private val systemAudioBackend: SystemAudioBackend`.
- All `ScreenCaptureJniBridge.nativeXxx(...)` call sites inside `RecordingSessionManager` replaced with `systemAudioBackend.xxx(...)`.
- `ScreenCaptureJniBridge` import removed from `RecordingSessionManager`.
- Call site (where `RecordingSessionManager` is instantiated) passes `SystemAudioBackendFactory.create()` (introduced in Story 2.6) — if 2.6 not yet done, pass `SilentAudioBackend()` as placeholder.
- All existing `RecordingSessionManager` tests pass; new tests exercise both `SilentAudioBackend` and a mock `SystemAudioBackend`.
- macOS end-to-end behavior unchanged.

**INVEST validation:**
- Independent: depends on interface (2.1) and wrapper (2.2); does not require Linux backend to exist.
- Negotiable: DI approach (constructor vs ambient service locator) already decided by architecture.
- Valuable: the central payoff of the abstraction — the manager is now cross-platform.
- Estimable: 2–3 hours including tests.
- Small: surgical change to one class; no new domain logic.
- Testable: existing tests become parameterizable with a mock backend.

**Estimated effort:** S

**Dependencies:** 2.2, 2.5 (or placeholder SilentAudioBackend)

---

### Story 2.4 — PipeWireCaptureBridge (C/JNI library)

**What:** Write a C JNI library `libPipeWireCaptureBridge.so` in `native/PipeWireCaptureBridge/` that captures PCM Float32 from a PipeWire monitor source using `pw_stream` with `PW_KEY_STREAM_CAPTURE_SINK`.

**Why:** Provides the Linux equivalent of `AudioCaptureBridgeJNI.dylib`; without it there is no system audio capture on Linux.

**Acceptance criteria:**
- C source at `native/PipeWireCaptureBridge/jni/PipeWireCaptureBridgeJNI.c` with these five JNI functions:
  `nativeCheckPermission`, `nativeRequestPermission`, `nativeStartCapture(sampleRate)`, `nativeStopCapture`, `nativeReadBuffer(outBuffer)`.
- Ring buffer (10s at 16kHz = 160,000 floats) protected by `pthread_mutex_t` (not `os_unfair_lock`).
- `nativeCheckPermission` checks for PipeWire socket existence at `$XDG_RUNTIME_DIR/pipewire-0`; returns `true` if present.
- `nativeRequestPermission` returns `true` immediately (no OS dialog on Linux).
- Audio format: `SPA_AUDIO_FORMAT_F32`, 16kHz, mono; matches existing channel format.
- Makefile at `native/PipeWireCaptureBridge/Makefile` mirrors the AudioCaptureBridge Makefile structure; uses `pkg-config --cflags/--libs libpipewire-0.3`; outputs `libPipeWireCaptureBridge.so` to `composeApp/src/desktopMain/resources/`.
- Compiles cleanly on Ubuntu 22.04 with `gcc` or `clang`.

**INVEST validation:**
- Independent: pure C/JNI; does not depend on any Kotlin change (Kotlin wrapper is Story 2.5).
- Negotiable: ring buffer size and lock type are adjustable.
- Valuable: the core Linux audio capture implementation; without it Story 2.5 has nothing to load.
- Estimable: L — native C threading + PipeWire API has complexity; estimate 1–2 days.
- Small: one C file, one Makefile; focused scope.
- Testable: a standalone C test harness can print samples to stdout before JNI wiring is complete.

**Estimated effort:** L

**Dependencies:** 1.1 (platform detection informs load-guard in Kotlin wrapper Story 2.5)

---

### Story 2.5 — PipeWireCaptureBackend (Kotlin wrapper)

**What:** Create `PipeWireCaptureBackend.kt` in `desktopMain/kotlin/audio/` that extracts `libPipeWireCaptureBridge.so` from classpath resources at init time and implements `SystemAudioBackend` by forwarding to its JNI functions.

**Why:** Bridges the C JNI library into the Kotlin layer using the same extraction pattern as `ScreenCaptureJniBridge`.

**Acceptance criteria:**
- `PipeWireCaptureBackend.kt` follows the exact two-path load strategy in `ScreenCaptureJniBridge.kt`: fast path via `System.loadLibrary`, slow path via resource extraction to temp dir prefixed `"agrapha-pipewire-jni"`.
- `isAvailable()` returns `true` only when `PlatformInfo.isLinux()` and the PipeWire socket exists.
- Load guard ensures `System.load()` is only called once per JVM.
- If `libPipeWireCaptureBridge.so` is not in classpath resources (i.e., the Makefile was not run), `isAvailable()` returns `false` with a log message — no crash.
- Unit test: with a mock JNI stub (or `SilentAudioBackend` standing in), verify `isAvailable()` returns `false` on macOS CI runners.

**INVEST validation:**
- Independent: Kotlin wrapper; does not need Story 2.4 to be fully working to write the class structure.
- Negotiable: temp directory naming and cleanup strategy are negotiable.
- Valuable: makes the C library usable from Kotlin.
- Estimable: 2–3 hours (mirrors existing pattern exactly).
- Small: ~80 lines.
- Testable: `isAvailable()` short-circuits on macOS; extraction logic is unit-testable with a fake resource.

**Estimated effort:** S

**Dependencies:** 2.1, 2.4

---

### Story 2.6 — SystemAudioBackendFactory

**What:** Create `SystemAudioBackendFactory` that selects the correct `SystemAudioBackend` implementation based on `PlatformInfo`.

**Why:** Centralizes platform dispatch for system audio; call sites instantiate `RecordingSessionManager` without knowing which backend is active.

**Acceptance criteria:**
- `SystemAudioBackendFactory.kt` in `desktopMain/kotlin/audio/`:
  ```kotlin
  object SystemAudioBackendFactory {
      fun create(): SystemAudioBackend = when (Platform.current) {
          Platform.Os.MACOS -> ScreenCaptureBackend()
          Platform.Os.LINUX -> PipeWireCaptureBackend()
          else              -> SilentAudioBackend()
      }
  }
  ```
- On macOS: `ScreenCaptureBackend` is returned; no behavior change.
- On Linux: `PipeWireCaptureBackend` is returned; if PipeWire is unavailable at runtime, the backend's `startCapture` returns `false` (graceful degradation — Story 2.5 handles this).
- On unknown OS: `SilentAudioBackend` returned; app does not crash.
- Unit tests cover all three branches using `PlatformInfo` test injection.

**INVEST validation:**
- Independent: depends on 2.2 and 2.5 existing; no business logic of its own.
- Negotiable: could be a top-level function instead of an object; negotiable.
- Valuable: completes the injection chain; wires everything together.
- Estimable: 1 hour.
- Small: ~20 lines.
- Testable: factory returns correct type per platform; mockable via override.

**Estimated effort:** S

**Dependencies:** 2.2, 2.5, 1.1

---

### Story 2.7 — Gradle build task for PipeWire C bridge

**What:** Add a Gradle `Exec` task in `composeApp/build.gradle.kts` that runs `make` in `native/PipeWireCaptureBridge/` on Linux, and extend the CI job from Story 1.3 to run it.

**Why:** Without an automated build step, `libPipeWireCaptureBridge.so` is missing from the resources jar, and the app silently falls back to silence on Linux.

**Acceptance criteria:**
- Gradle task `buildPipeWireBridge` of type `Exec` added to `build.gradle.kts`, guarded by `if (org.gradle.internal.os.OperatingSystem.current().isLinux)`.
- Task wired as a dependency of `desktopProcessResources` so the `.so` is in the classpath before the app runs.
- CI job `build-linux` (Story 1.3) runs `sudo apt-get install -y libpipewire-0.3-dev` before the Gradle build.
- `make clean` target clears the `.so` from `resources/`; Gradle `clean` task depends on it.
- macOS CI job is unaffected (task is a no-op on non-Linux).

**INVEST validation:**
- Independent: Gradle plumbing only; no new Kotlin or C code.
- Negotiable: task name and dependency wiring are open.
- Valuable: makes the Linux audio build reproducible and automated.
- Estimable: 2–3 hours.
- Small: Gradle DSL changes only.
- Testable: after the task runs, `find resources/ -name "*.so"` returns the file.

**Estimated effort:** S

**Dependencies:** 2.4, 1.3

---

## Epic 3: Plugin Loading Infrastructure

**Goal:** Define the `SpeechOutputPlugin` SPI and a `PluginLoader` capable of discovering external JARs from `~/.config/agrapha/plugins/`, with enable/disable UI.

---

### Story 3.1 — DictationMode enum

**What:** Define the `DictationMode` enum in `commonMain` with values `PUSH_TO_TALK`, `FILE_TRANSCRIPTION`, `LIVE_CAPTIONS`.

**Why:** `SpeechOutputPlugin.supportedModes` must reference this type from shared code; plugins shipped as separate JARs must compile against the commonMain API.

**Acceptance criteria:**
- `DictationMode.kt` in `composeApp/src/commonMain/kotlin/domain/plugin/`.
- Three values: `PUSH_TO_TALK`, `FILE_TRANSCRIPTION`, `LIVE_CAPTIONS`.
- Serializable via `kotlinx.serialization` (`@Serializable` annotation) for settings persistence.
- No desktopMain or platform-specific imports.
- Trivial test: enum values can be round-tripped through JSON serialization.

**INVEST validation:**
- Independent: no dependencies; can be created on day one.
- Negotiable: additional future modes (e.g., `REAL_TIME_TRANSLATION`) can be added without breaking existing plugins.
- Valuable: the shared vocabulary that all plugin JARs compile against.
- Estimable: 30 minutes.
- Small: one file, ~10 lines.
- Testable: serialization round-trip test.

**Estimated effort:** S

**Dependencies:** none

---

### Story 3.2 — SpeechOutputPlugin interface

**What:** Define the `SpeechOutputPlugin` interface in `commonMain` with `id`, `name`, `supportedModes`, `activate(mode, config)`, `deactivate()`, and `close()` lifecycle.

**Why:** This is the SPI contract that all dictation plugins — built-in or third-party — must implement; it must be in `commonMain` so plugin JARs compile against a minimal dependency.

**Acceptance criteria:**
- `SpeechOutputPlugin.kt` in `composeApp/src/commonMain/kotlin/domain/plugin/`.
- Interface:
  ```kotlin
  interface SpeechOutputPlugin {
      val id: String
      val name: String
      val supportedModes: Set<DictationMode>
      fun activate(mode: DictationMode, config: Map<String, String>)
      fun deactivate()
      fun close()   // lifecycle: called before classloader is closed
  }
  ```
- `PluginException` class in the same file for plugin-originated errors.
- No desktopMain or JNI imports.
- Unit test: an anonymous object implementing the interface compiles and can be assigned to `SpeechOutputPlugin`.

**INVEST validation:**
- Independent: depends only on `DictationMode` (3.1); no platform code.
- Negotiable: `config` type (`Map<String, String>` vs a typed config class) is open.
- Valuable: defines the extension point; everything else in Epics 3–5 depends on this.
- Estimable: 1 hour.
- Small: ~40 lines.
- Testable: implement-and-assign test; interface contract tests via anonymous implementation.

**Estimated effort:** S

**Dependencies:** 3.1

---

### Story 3.3 — PluginLoader

**What:** Implement `PluginLoader` in `desktopMain` using `java.util.ServiceLoader` + `URLClassLoader` to discover and load plugin JARs from `~/.config/agrapha/plugins/`; isolate each plugin in its own classloader with child-first delegation.

**Why:** Enables third-party plugins to be dropped into a directory and loaded without recompilation; isolation prevents version conflicts and memory leaks (see pitfalls).

**Acceptance criteria:**
- `PluginLoader.kt` in `desktopMain/kotlin/plugin/`.
- `loadAll(pluginDir: File): List<PluginLoadResult>` where `PluginLoadResult` is a sealed class: `Success(plugin: SpeechOutputPlugin)` or `Failure(jarPath: String, error: Throwable)`.
- Each JAR gets its own `URLClassLoader` with parent = the host app's classloader (child-first delegation override).
- A crashing plugin's `ServiceLoader.load()` is caught per-JAR; failure is returned as `PluginLoadResult.Failure`, not an exception.
- `unload(pluginId: String)` calls `plugin.close()` then `classLoader.close()` for the corresponding JAR.
- Unit tests: a test JAR on the classpath path is loaded and its plugin instantiated; a broken JAR returns `Failure` without affecting other plugins.

**INVEST validation:**
- Independent: depends on 3.2 interface; does not require any plugin implementation to exist.
- Negotiable: plugin directory path (`~/.config/agrapha/plugins/` vs configurable) is open.
- Valuable: unlocks the entire extension ecosystem.
- Estimable: M — classloader isolation and error containment have gotchas.
- Small: one file; ~120 lines.
- Testable: test with a real tiny JAR in src/desktopTest/resources/testplugin.jar.

**Estimated effort:** M

**Dependencies:** 3.2

---

### Story 3.4 — AppSettings addition for enabled plugins

**What:** Add `enabledPlugins: Map<String, Boolean>` keyed by plugin `id` to `AppSettings` with default `emptyMap()`.

**Why:** Persists the user's per-plugin enable/disable decision across restarts; without this, all plugins are loaded on every start regardless of preference.

**Acceptance criteria:**
- `AppSettings` (located in commonMain domain model) gains `val enabledPlugins: Map<String, Boolean> = emptyMap()`.
- Serialization: `@Serializable` already covers the map; verify round-trip through existing settings persistence layer.
- Migration: reading old settings files missing this field defaults to `emptyMap()` (kotlinx.serialization handles missing keys with defaults).
- Unit test: a settings JSON without `enabledPlugins` deserializes to `emptyMap()`.

**INVEST validation:**
- Independent: `AppSettings` change does not require `PluginLoader` to be written.
- Negotiable: storage key name and default are open.
- Valuable: enables UI Story 3.5 to persist state.
- Estimable: 30 minutes.
- Small: one field addition + migration test.
- Testable: JSON round-trip test.

**Estimated effort:** S

**Dependencies:** 3.2

---

### Story 3.5 — Settings UI — plugin list with enable/disable toggle

**What:** Add a "Plugins" section to the Settings screen showing loaded plugins with an enable/disable toggle; plugin load errors appear inline without crashing the UI.

**Why:** Users need a discoverability surface for installed plugins and a way to disable misbehaving ones.

**Acceptance criteria:**
- New `PluginsSettingsSection` composable in `desktopMain/kotlin/ui/settings/`.
- Renders a list of `PluginLoadResult` items: `Success` shows plugin name + toggle; `Failure` shows jar path + error message in a warning style.
- Toggle inverts the corresponding `AppSettings.enabledPlugins[plugin.id]` value and persists via the settings ViewModel.
- Disabling a plugin calls `PluginLoader.unload(pluginId)` immediately.
- If `~/.config/agrapha/plugins/` directory does not exist, section shows a "No plugins installed" empty state.
- UI test (Compose test): mock `PluginLoader` returns one `Success` and one `Failure`; both render without crashing.

**INVEST validation:**
- Independent: UI only; can be built against a mock `PluginLoader`.
- Negotiable: visual design of the error inline state.
- Valuable: users see and control their plugins.
- Estimable: M — Compose settings screen additions with state handling.
- Small: one composable + ViewModel wiring.
- Testable: Compose UI test with mocked loader.

**Estimated effort:** M

**Dependencies:** 3.3, 3.4

---

## Epic 4: TextInjector Abstraction

**Goal:** Define a `TextInjector` interface and implement `ydotool`, `xdotool`, and auto-detect backends so dictation can inject text on both Wayland and X11.

---

### Story 4.1 — TextInjector interface + TextInjectorUnavailableException

**What:** Define `TextInjector` interface and `TextInjectorUnavailableException` in `desktopMain`.

**Why:** Decouples dictation logic from the specific injection tool; enables future backends (e.g., `wtype`) without changing `DictationPlugin`.

**Acceptance criteria:**
- `TextInjector.kt` in `desktopMain/kotlin/injection/`:
  ```kotlin
  interface TextInjector {
      fun inject(text: String): Result<Unit>
      fun isAvailable(): Boolean
  }
  class TextInjectorUnavailableException(message: String) : Exception(message)
  ```
- `inject()` returns `Result.failure(TextInjectorUnavailableException(...))` when the injector is not available — never throws.
- Unit test: a trivial test implementation of `TextInjector` compiles and returns `Result.success(Unit)`.

**INVEST validation:**
- Independent: pure interface definition; no subprocess or platform code.
- Negotiable: `Result<Unit>` vs checked exception is a design choice; current choice avoids coroutine complexity.
- Valuable: defines the text injection contract used by Story 5.2.
- Estimable: 30 minutes.
- Small: ~30 lines.
- Testable: compile test; mock test.

**Estimated effort:** S

**Dependencies:** none

---

### Story 4.2 — YdotoolTextInjector

**What:** Implement `YdotoolTextInjector` that shells out to `ydotool type -- "<text>"` with health-check logic (`NOT_INSTALLED`, `DAEMON_NOT_RUNNING`, `OK`).

**Why:** ydotool is the universal text injection tool (Wayland + X11) and the preferred backend; it requires a running daemon, so detection must be explicit.

**Acceptance criteria:**
- `YdotoolTextInjector.kt` in `desktopMain/kotlin/injection/`.
- `isAvailable()` checks: (1) `which ydotool` exits 0; (2) `pgrep -x ydotoold` exits 0 OR `/tmp/.ydotool_socket` exists.
- `inject(text)` sanitizes text (strips non-printable characters, null bytes; does not shell-escape via quotes — uses `ProcessBuilder` varargs form to avoid shell injection).
- Injection uses `ProcessBuilder("ydotool", "type", "--", text)` — the `--` prevents flag injection.
- `YdotoolStatus` enum: `NOT_INSTALLED`, `DAEMON_NOT_RUNNING`, `OK`; exposed via `checkStatus(): YdotoolStatus`.
- Unit tests mock the subprocess with a test `ProcessBuilder` factory; no actual `ydotool` required in CI.

**INVEST validation:**
- Independent: depends only on the interface (4.1); no plugin or dictation code.
- Negotiable: `--key-delay` flag tuning; socket path override for testing.
- Valuable: the primary injection path for Wayland users.
- Estimable: M — subprocess mocking and sanitization edge cases take time.
- Small: one file; ~100 lines.
- Testable: unit tests via injectable `ProcessBuilderFactory`.

**Estimated effort:** M

**Dependencies:** 4.1

---

### Story 4.3 — XdotoolTextInjector

**What:** Implement `XdotoolTextInjector` that shells out to `xdotool type --clearmodifiers "<text>"` with an X11-only guard that fails gracefully on Wayland.

**Why:** xdotool is the fallback for X11 sessions; it must refuse to run on Wayland (where it does not work) rather than silently producing no output.

**Acceptance criteria:**
- `XdotoolTextInjector.kt` in `desktopMain/kotlin/injection/`.
- `isAvailable()` returns `false` if `WAYLAND_DISPLAY` is set AND `DISPLAY` is not set (pure Wayland without XWayland).
- `isAvailable()` also checks `which xdotool` exits 0.
- `inject(text)` uses `ProcessBuilder("xdotool", "type", "--clearmodifiers", "--", text)`.
- Same text sanitization as Story 4.2.
- Unit tests: returns `false` when `WAYLAND_DISPLAY` is set and `DISPLAY` is absent; mocked subprocess test.

**INVEST validation:**
- Independent: depends on interface (4.1); parallel with Story 4.2.
- Negotiable: XWayland detection logic (checking both env vars) is open.
- Valuable: provides a fallback for the X11 user segment with no daemon setup.
- Estimable: S — simpler than ydotool (no daemon check).
- Small: ~80 lines.
- Testable: env var injection in unit test; mocked process.

**Estimated effort:** S

**Dependencies:** 4.1

---

### Story 4.4 — AutoDetectTextInjector

**What:** Implement `AutoDetectTextInjector` that tries `YdotoolTextInjector` first, falls back to `XdotoolTextInjector`, and throws `TextInjectorUnavailableException` if neither is available.

**Why:** Callers (Story 5.2) should not need to know which tool is installed; auto-detection at runtime gives users the best available experience.

**Acceptance criteria:**
- `AutoDetectTextInjector.kt` in `desktopMain/kotlin/injection/`.
- Constructor accepts `candidates: List<TextInjector>` (default: `listOf(YdotoolTextInjector(), XdotoolTextInjector())`); selected on first call to `isAvailable()`.
- `isAvailable()` iterates candidates in order; returns `true` for the first one that is available; caches the selection.
- `inject(text)` delegates to the cached candidate, or returns `Result.failure(TextInjectorUnavailableException(...))` if none were available.
- Logs (at INFO level) which injector was selected.
- Unit tests: mock candidate list where ydotool unavailable → xdotool used; both unavailable → `TextInjectorUnavailableException`.

**INVEST validation:**
- Independent: depends on 4.2 and 4.3; no plugin code.
- Negotiable: candidate order (ydotool-first) is a policy choice; can be reversed or made configurable.
- Valuable: zero-config experience for users — the right injector is selected automatically.
- Estimable: S.
- Small: ~60 lines.
- Testable: mock `TextInjector` list covers all branches.

**Estimated effort:** S

**Dependencies:** 4.2, 4.3

---

## Epic 5: Dictation Plugin (Built-in SPI)

**Goal:** Implement `DictationPlugin` as a built-in `SpeechOutputPlugin` covering push-to-talk, file transcription, and live captions modes; register it via `META-INF/services/` for `ServiceLoader`.

---

### Story 5.1 — DictationPlugin shell

**What:** Create `DictationPlugin` implementing `SpeechOutputPlugin` with stubbed `activate` / `deactivate` / `close` and correct `id`, `name`, `supportedModes`.

**Why:** Establishes the class skeleton that Stories 5.2–5.4 fill in; ensures the plugin registers and loads before any mode logic is written.

**Acceptance criteria:**
- `DictationPlugin.kt` in `desktopMain/kotlin/plugin/dictation/`.
- `id = "com.agrapha.dictation"`, `name = "Dictation"`, `supportedModes = setOf(PUSH_TO_TALK, FILE_TRANSCRIPTION, LIVE_CAPTIONS)`.
- `activate(mode, config)` throws `UnsupportedOperationException("mode not yet implemented")` for all modes (to be replaced in 5.2–5.4).
- `close()` is a no-op in the shell.
- Unit test: plugin instantiation, correct `id`/`name`/`supportedModes`.

**INVEST validation:**
- Independent: depends on interface (3.2); no other Epic 5 stories.
- Negotiable: initial stub behavior; `UnsupportedOperationException` vs silent no-op.
- Valuable: lets Stories 5.2–5.4 and 5.5 proceed independently of each other.
- Estimable: 30 minutes.
- Small: ~30 lines.
- Testable: instantiation test.

**Estimated effort:** S

**Dependencies:** 3.2, 4.4

---

### Story 5.2 — PUSH_TO_TALK mode

**What:** Implement the `PUSH_TO_TALK` branch in `DictationPlugin.activate`: register a global hotkey, record mic on hold, transcribe on release, inject text.

**Why:** This is the primary user value of the dictation plugin — hands-free text injection into any focused window.

**Acceptance criteria:**
- On `activate(PUSH_TO_TALK, config)`: reads `config["hotkey"]` (default `"super+space"`); registers via a `GlobalHotkeyProvider` interface (X11: `XGrabKey` via JNA; Wayland: `xdg-desktop-portal` GlobalShortcuts portal).
- On hotkey press: starts a short mic recording via `MicCaptureService`.
- On hotkey release: stops recording, submits audio to `WhisperService` for transcription.
- On transcription complete: calls `AutoDetectTextInjector.inject(transcribedText)`.
- If `GlobalHotkeyProvider` is unavailable (Wayland without portal): `activate()` logs a warning and surface a user-visible message via `PluginException`; does not crash.
- Total latency from hotkey release to injection < 1.5s for a 5-word utterance on AVX2 CPU.
- Unit tests mock `MicCaptureService`, `WhisperService`, and `TextInjector`; verify the call sequence.

**INVEST validation:**
- Independent: depends on shell (5.1) and injector (4.4); does not require Epic 2 to be fully wired.
- Negotiable: hotkey registration library (JNA vs dbus-java vs custom JNI); Wayland fallback behavior.
- Valuable: the core feature users requested.
- Estimable: L — global hotkey on Wayland/X11 is the riskiest implementation in the entire project.
- Small: focused on one mode; FILE_TRANSCRIPTION and LIVE_CAPTIONS are separate stories.
- Testable: full unit test with mocked dependencies; integration test requires hardware.

**Estimated effort:** L

**Dependencies:** 5.1, 1.1

---

### Story 5.3 — FILE_TRANSCRIPTION mode

**What:** Implement the `FILE_TRANSCRIPTION` branch: read an audio file path from config, run `WhisperService`, write the transcript to stdout or a configured output path.

**Why:** Enables batch transcription use cases (transcribe a downloaded recording) without requiring the recording session pipeline.

**Acceptance criteria:**
- On `activate(FILE_TRANSCRIPTION, config)`: reads `config["inputPath"]`; validates file exists and is readable.
- Calls `WhisperService.transcribe(File(inputPath))`.
- Writes result to `config["outputPath"]` if set, otherwise to stdout.
- If `inputPath` is missing or file not found, `activate()` returns a `Result.failure` wrapped in `PluginException` — does not crash the plugin loader.
- Unit test: mock `WhisperService` returning a fixed transcript; verify output written to a temp file.

**INVEST validation:**
- Independent: depends on shell (5.1); no hotkey, injection, or UI code.
- Negotiable: stdin as input source could be added later; not in scope.
- Valuable: useful standalone without the full recording UI.
- Estimable: S.
- Small: ~60 lines.
- Testable: fully unit-testable with mocked `WhisperService`.

**Estimated effort:** S

**Dependencies:** 5.1

---

### Story 5.4 — LIVE_CAPTIONS mode

**What:** Implement the `LIVE_CAPTIONS` branch: start an always-on mic listener with `MicCaptureService`, stream Whisper segments to a floating Compose overlay window.

**Why:** Provides real-time subtitle-style captions for accessibility and users who want continuous transcription.

**Acceptance criteria:**
- On `activate(LIVE_CAPTIONS, config)`: opens a frameless, always-on-top Compose `Window` (using `application { Window(...) }` in a separate coroutine scope).
- `MicCaptureService.captureFlow()` streams audio; every ~3s chunk is submitted to `WhisperService`.
- Transcribed segment text appended to overlay window; last N segments kept visible (configurable via `config["maxSegments"]`, default 5).
- On `deactivate()`: mic capture stops, overlay window closes.
- On `close()`: same as `deactivate()`; no coroutine leak.
- UI test (Compose): mock `WhisperService` emitting segments; verify segments appear in overlay composable.

**INVEST validation:**
- Independent: depends on shell (5.1); no hotkey or injection code.
- Negotiable: overlay window style and positioning are open.
- Valuable: delivers real-time captions — distinct value proposition from PUSH_TO_TALK.
- Estimable: M — Compose windowing on desktop has some quirks.
- Small: focused on one mode.
- Testable: Compose UI test for overlay; mic/Whisper mocking for pipeline.

**Estimated effort:** M

**Dependencies:** 5.1

---

### Story 5.5 — ServiceLoader registration

**What:** Add `META-INF/services/com.meetingnotes.domain.plugin.SpeechOutputPlugin` to `desktopMain/resources/` listing `DictationPlugin`'s fully-qualified class name.

**Why:** Without this file, `PluginLoader` (Story 3.3) will not discover the built-in `DictationPlugin` via `ServiceLoader`; the plugin is invisible to the framework.

**Acceptance criteria:**
- File `composeApp/src/desktopMain/resources/META-INF/services/com.meetingnotes.domain.plugin.SpeechOutputPlugin` created with content: `com.meetingnotes.plugin.dictation.DictationPlugin`.
- Integration test: `PluginLoader.loadAll(builtinPluginDir)` — where the dir contains the compiled JAR — returns a list including `DictationPlugin` with `id == "com.agrapha.dictation"`.
- `PluginLoader` from Story 3.3 discovers the built-in plugin in the same way it discovers external JARs.

**INVEST validation:**
- Independent: depends on shell (5.1) and loader (3.3) being written; is itself a single file addition.
- Negotiable: built-in plugin could alternatively be registered programmatically; ServiceLoader approach is consistent.
- Valuable: makes the built-in plugin discoverable; completes the plugin lifecycle.
- Estimable: 30 minutes.
- Small: one file.
- Testable: integration test via ServiceLoader in desktopTest.

**Estimated effort:** S

**Dependencies:** 5.1, 3.3

---

## Known Risks and Mitigations

Sourced from `research/pitfalls.md` and `requirements.md`.

| # | Risk | Severity | Affected Stories | Mitigation |
|---|---|---|---|---|
| R1 | PipeWire monitor source unavailable (socket missing, Flatpak sandbox) | High | 2.4, 2.5, 2.6 | `PipeWireCaptureBackend.isAvailable()` checks socket; returns `false` → `SilentAudioBackend` fallback. Flatpak not targeted this phase. |
| R2 | ydotoold daemon not running on fresh installs | High | 4.2, 5.2 | `YdotoolStatus` enum displayed in Settings + DictationPlugin activation error message; xdotool fallback via `AutoDetectTextInjector`. |
| R3 | whisper-jni AVX2 requirement: SIGILL on pre-Haswell CPUs | High | 1.2, (WhisperService) | CPU flag check via `/proc/cpuinfo` before `WhisperJNI.loadLibrary()`; friendly error dialog if check fails. Add check in Story 1.2. |
| R4 | whisper-jni GLIBC 2.31 floor: fails on RHEL 8 (GLIBC 2.28) | Medium | 1.2, 1.3 | Document minimum distro requirements; CI uses Ubuntu 22.04 (GLIBC 2.35). |
| R5 | Global hotkey impossible on GNOME Wayland without portal | High | 5.2 | GlobalShortcuts portal path (xdg-desktop-portal ≥ 1.16 required); document evdev fallback; in-app focus-required mode as last resort. |
| R6 | URLClassLoader memory leak on plugin reload | Medium | 3.3 | `PluginLoader.unload()` calls `plugin.close()` then `URLClassLoader.close()`; document that plugins must not use static `ThreadLocal` or `LogManager`. |
| R7 | Native whisper-jni thread-safety: concurrent calls crash | Medium | 5.2, 5.4 | `WhisperService` must be serialized (existing behavior); document in `SpeechOutputPlugin` SPI that plugins must not call `WhisperJNI` directly. |
| R8 | xdotool does nothing on pure Wayland | Medium | 4.3 | `XdotoolTextInjector.isAvailable()` returns `false` on pure Wayland; auto-detect falls back or raises `TextInjectorUnavailableException`. |
| R9 | libstdc++ missing on minimal server installs | Low | 1.2 | Document `libstdc++6` as a prerequisite; not expected on desktop installs. |

---

## ADR Stubs

The following architectural decision records should be written before starting the indicated stories. Each stub defines the question to be answered; the full ADR goes in `project_plans/linux-dictation-plugin/decisions/`.

---

### ADR-001 — SystemAudioBackend injection strategy

**Status:** Proposed
**Context:** `RecordingSessionManager` currently calls `ScreenCaptureJniBridge` directly. We need it to work on both macOS and Linux. Three options exist: (a) constructor injection with a factory, (b) `expect/actual` at the Kotlin Multiplatform level, (c) service locator pattern.
**Decision question:** Should `SystemAudioBackend` be injected via constructor (chosen in this plan), or should `expect/actual` be used to keep `RecordingSessionManager` in commonMain?
**Constraints:** `expect/actual` would require moving `RecordingSessionManager` to commonMain, which currently has no audio recording logic and would increase refactoring risk. Constructor injection is narrower.
**Stories blocked until resolved:** 2.3
**Recommendation:** Constructor injection (option a) — minimal blast radius; macOS path unchanged.

---

### ADR-002 — Plugin classloader isolation approach

**Status:** Proposed
**Context:** Plugin JARs loaded via `URLClassLoader` can conflict with host-app class versions (especially `whisper-jni`), and classloaders can leak if plugins hold static state.
**Decision question:** Should plugins use (a) parent-first `URLClassLoader` (JVM default), (b) child-first `URLClassLoader` (isolated), or (c) a module-system-based approach (JPMS `ModuleLayer`)?
**Constraints:** JPMS requires all JARs to be named modules — existing code and third-party JARs are not. Child-first URLClassLoader is the proven ServiceLoader pattern for OSGi-free plugin systems.
**Stories blocked until resolved:** 3.3
**Recommendation:** Child-first `URLClassLoader` (option b). Mark `whisper-jni` and the plugin SPI JAR as provided-scope in plugin documentation so they use the host's loaded native libs.

---

### ADR-003 — Global hotkey approach on Wayland vs X11

**Status:** Proposed
**Context:** Push-to-talk requires a global hotkey that fires regardless of which window is focused. On X11, `XGrabKey` via JNA is standard. On Wayland, only the `xdg-desktop-portal` GlobalShortcuts portal (stable in portal ≥ 1.16 / GNOME 46+ / KDE Plasma 6) is a cross-compositor standard. evdev polling works everywhere but has security implications (reads all keypresses).
**Decision question:** Should Story 5.2 implement (a) portal-first with evdev fallback, (b) X11-only with documented Wayland limitation, (c) in-window hotkey only for MVP?
**Constraints:** Portal requires D-Bus bindings (adds dependency: `dbus-java` or JNA). evdev requires `input` group (same as ydotool). X11-only is simplest but excludes GNOME Wayland users.
**Stories blocked until resolved:** 5.2
**Recommendation:** Option (c) — in-window focus-required shortcut for MVP; portal implementation as a follow-up story. Document clearly in Settings that global push-to-talk requires compositor portal support. Revisit after platform validation (Story 1.2).

---

## Story Summary Table

| Story | Title | Effort | Epic | Dependencies |
|---|---|---|---|---|
| 1.1 | PlatformInfo utility | S | 1 | — |
| 1.2 | Verify Linux baseline | S | 1 | — |
| 1.3 | Gradle Linux CI job | S | 1 | 1.2 |
| 2.1 | SystemAudioBackend interface | S | 2 | 1.1 |
| 2.2 | ScreenCaptureBackend adapter | S | 2 | 2.1 |
| 2.3 | RecordingSessionManager refactor | S | 2 | 2.2, 2.5 |
| 2.4 | PipeWireCaptureBridge (C/JNI) | L | 2 | 1.1 |
| 2.5 | PipeWireCaptureBackend (Kotlin) | S | 2 | 2.1, 2.4 |
| 2.6 | SystemAudioBackendFactory | S | 2 | 2.2, 2.5, 1.1 |
| 2.7 | Gradle PipeWire build task | S | 2 | 2.4, 1.3 |
| 3.1 | DictationMode enum | S | 3 | — |
| 3.2 | SpeechOutputPlugin interface | S | 3 | 3.1 |
| 3.3 | PluginLoader | M | 3 | 3.2 |
| 3.4 | AppSettings enabledPlugins | S | 3 | 3.2 |
| 3.5 | Settings UI plugin list | M | 3 | 3.3, 3.4 |
| 4.1 | TextInjector interface | S | 4 | — |
| 4.2 | YdotoolTextInjector | M | 4 | 4.1 |
| 4.3 | XdotoolTextInjector | S | 4 | 4.1 |
| 4.4 | AutoDetectTextInjector | S | 4 | 4.2, 4.3 |
| 5.1 | DictationPlugin shell | S | 5 | 3.2, 4.4 |
| 5.2 | PUSH_TO_TALK mode | L | 5 | 5.1, 1.1 |
| 5.3 | FILE_TRANSCRIPTION mode | S | 5 | 5.1 |
| 5.4 | LIVE_CAPTIONS mode | M | 5 | 5.1 |
| 5.5 | ServiceLoader registration | S | 5 | 5.1, 3.3 |

**Totals:** 5 epics, 22 stories — 14 Small / 5 Medium / 3 Large

---

## Parallelization Opportunities

The following stories have no dependencies on each other and can be worked in parallel by separate engineers:

- **Immediate start (no deps):** 1.1, 1.2, 3.1, 4.1
- **After 1.1:** 2.1, 2.4 (parallel)
- **After 3.1:** 3.2
- **After 4.1:** 4.2, 4.3 (parallel)
- **After 2.1:** 2.2 and 2.5 begin (2.5 waits on 2.4 too)
- **After 3.2:** 3.3, 3.4 (parallel); then 5.1 after 4.4 is also done

---

## Implementation Order (Recommended)

**Sprint 1 (foundation):**
1.1, 1.2, 3.1, 4.1

**Sprint 2 (interfaces + CI):**
1.3, 2.1, 3.2, 4.2, 4.3

**Sprint 3 (implementations):**
2.2, 2.4 (parallel), 3.3, 3.4, 4.4

**Sprint 4 (wiring + plugin shell):**
2.5, 2.6, 2.7, 2.3, 3.5, 5.1

**Sprint 5 (dictation modes):**
5.2, 5.3, 5.4, 5.5
