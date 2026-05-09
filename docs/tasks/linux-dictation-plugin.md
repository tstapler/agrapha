# Feature Plan: Linux Dictation Plugin — Remaining Work (PR #1 Merge Gate)

**Project:** linux-dictation-plugin
**Branch:** feature/linux-dictation-plugin (PR #1)
**Date:** 2026-05-09
**Status:** One story remaining before merge

---

## Epic Overview

The linux-dictation-plugin feature is 21 of 22 stories complete. The single remaining story is the
Linux CI job (Story 1.3), which is the merge gate for PR #1. Without it, the PipeWire Rust build
and all 194 tests are validated only on macOS in CI.

**What has already been implemented (all passing tests):**
- PlatformInfo utility (Story 1.1)
- Full SystemAudioBackend abstraction chain: interface, ScreenCaptureBackend, PipeWireCaptureBackend,
  NoOpSystemAudioBackend, SystemAudioBackendFactory, RecordingSessionManager refactor (Stories 2.1-2.7)
- Plugin SPI: DictationMode, SpeechOutputPlugin, PluginLoader, AppSettings.enabledPlugins,
  PluginsSettingsSection (Stories 3.1-3.5)
- TextInjector abstraction: interface, YdotoolTextInjector, XdotoolTextInjector,
  AutoDetectTextInjector (Stories 4.1-4.4)
- DictationPlugin with all three modes, HotkeyService, ServiceLoader registration (Stories 5.1-5.5)
- Rust JNI crate unifying PipeWire audio + X11/Wayland hotkeys + macOS ScreenCaptureKit audio

---

## Story 1.3 — Gradle Linux CI Job

**Status:** Pending (PR #1 merge blocker)
**Effort:** S (2-3 hours)
**Dependencies:** None (Rust crate already builds; tests already pass)

### Scope

Add a `build-linux` job to `.github/workflows/build.yml` that:
1. Installs Rust toolchain + PipeWire dev headers
2. Runs `cargo build --release` in `native/agrapha-native/` (via the existing `buildAgraphaNative` Gradle task)
3. Runs `./gradlew :composeApp:desktopTest` under `xvfb-run`

### Files

- `.github/workflows/build.yml` (modify — add job alongside existing `build` job)
- No Kotlin or Rust changes required

### Context

The existing `build` job runs on `macos-14` and:
- Builds `native/WhisperCoreML/` (CoreML dylib — macOS only)
- Builds `native/AudioCaptureBridge/` (Swift+ObjC dylib — superseded by Rust crate but job still references it)
- Runs `./gradlew :composeApp:desktopTest`
- Runs `./gradlew :composeApp:packageReleaseDmg`

The `buildAgraphaNative` Gradle task in `composeApp/build.gradle.kts` already handles the Cargo
invocation and copies the `.so` to `src/desktopMain/resources/`. The CI job just needs to ensure
prerequisites are installed before the Gradle task runs.

### Implementation

```yaml
  build-linux:
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Check out
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Install Rust toolchain
        uses: dtolnay/rust-toolchain@stable

      - name: Cache Cargo registry
        uses: actions/cache@v4
        with:
          path: |
            ~/.cargo/registry
            ~/.cargo/git
            native/agrapha-native/target
          key: ${{ runner.os }}-cargo-${{ hashFiles('native/agrapha-native/Cargo.lock') }}
          restore-keys: |
            ${{ runner.os }}-cargo-

      - name: Install PipeWire and X11 dev headers
        run: |
          sudo apt-get update -q
          sudo apt-get install -y --no-install-recommends \
            libpipewire-0.3-dev \
            libspa-0.2-dev \
            libx11-dev \
            libx11-xcb-dev \
            xvfb \
            ydotool

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Run desktop tests (includes Rust build via buildAgraphaNative)
        run: xvfb-run ./gradlew :composeApp:desktopTest --no-daemon
```

### Notes on the existing macOS job

The existing `build` job still runs `native/AudioCaptureBridge/make`. The Swift+ObjC bridge is
superseded by the Rust crate on macOS but the job references the old Makefile. Options:
- (a) Leave the macOS job as-is (AudioCaptureBridge Makefile produces the `.dylib` but it is
  overwritten by the Rust build — harmless but wastes ~30 seconds)
- (b) Remove the `Build AudioCaptureBridge dylib` step from the macOS job since `buildAgraphaNative`
  now covers macOS via `libagrapha_native.dylib`

Option (b) is cleaner but is a separate, lower-priority cleanup. Do not block the Linux CI job on it.

### Success Criteria

- [ ] `.github/workflows/build.yml` has a `build-linux` job that runs in parallel with `build`
- [ ] `build-linux` passes on `ubuntu-latest` with all 194 tests green
- [ ] PipeWire apt packages listed in CI match the crate dependencies in `native/agrapha-native/Cargo.toml`
- [ ] Cargo cache key is based on `Cargo.lock` to avoid stale cache on dependency changes
- [ ] `xvfb-run` wraps the Gradle test command so AWT-dependent tests do not fail headlessly

### Testing

The test is the CI job itself. Locally, verify with:
```bash
# Simulate the apt installs (on Ubuntu/Debian)
sudo apt-get install -y libpipewire-0.3-dev libspa-0.2-dev libx11-dev libx11-xcb-dev xvfb

# Run the same Gradle command CI will run
xvfb-run ./gradlew :composeApp:desktopTest --no-daemon
```

---

## Dependency Graph

```
Story 1.3 — Linux CI job [PENDING — PR #1 merge gate]
  (no dependencies; all implementation stories complete)
```

---

## Progress

| Story | Title | Status |
|---|---|---|
| 1.1 | PlatformInfo utility | Completed |
| 1.2 | Verify Linux baseline | Completed (via Rust crate + CI will confirm) |
| 1.3 | Gradle Linux CI job | Pending |
| 2.1 | SystemAudioBackend interface | Completed |
| 2.2 | ScreenCaptureBackend adapter | Completed |
| 2.3 | RecordingSessionManager refactor | Completed |
| 2.4 | PipeWireCaptureBridge (Rust) | Completed |
| 2.5 | PipeWireCaptureBackend (Kotlin) | Completed |
| 2.6 | SystemAudioBackendFactory | Completed |
| 2.7 | Gradle native build task | Completed |
| 3.1 | DictationMode enum | Completed |
| 3.2 | SpeechOutputPlugin interface | Completed |
| 3.3 | PluginLoader | Completed |
| 3.4 | AppSettings.enabledPlugins | Completed |
| 3.5 | Settings UI plugin list | Completed |
| 4.1 | TextInjector interface | Completed |
| 4.2 | YdotoolTextInjector | Completed |
| 4.3 | XdotoolTextInjector | Completed |
| 4.4 | AutoDetectTextInjector | Completed |
| 5.1 | DictationPlugin shell | Completed |
| 5.2 | PUSH_TO_TALK mode | Completed |
| 5.3 | FILE_TRANSCRIPTION mode | Completed |
| 5.4 | LIVE_CAPTIONS mode | Completed |
| 5.5 | ServiceLoader registration | Completed |

**Progress: 21/22 stories complete (95%)**

---

## Post-Merge Work (Not Required for PR #1)

### whisper-jni AVX2 Guard (Risk R3)

`PlatformInfo.avx2Supported()` is already implemented. `WhisperService` does not call it before
loading the model. On pre-Haswell Linux CPUs this causes a SIGILL crash.

**Fix:** Add an AVX2 check in `WhisperService.loadModel()` or at app startup, surfacing a dialog
rather than crashing.

Files: `WhisperService.kt`, `PlatformInfo.kt`, optionally `RecordingViewModel.kt`
Effort: S (1-2 hours)

### LIVE_CAPTIONS Floating Overlay Window

`DictationPlugin.activateLiveCaptions()` streams segments into a `liveSegments: StateFlow<List<String>>`.
The floating Compose `Window` (always-on-top, frameless) described in plan Story 5.4 is not yet created.
A consumer must observe `DictationPlugin.liveSegments` and render an overlay.

Files: new `LiveCaptionsOverlay.kt`, `DictationPlugin.kt`, `Main.kt` or `AppRoot.kt`
Effort: M (3-4 hours)
Tracked in: plan Story 5.4 acceptance criteria

### Cleanup: Remove Stale Swift/ObjC AudioCaptureBridge from macOS CI

`native/AudioCaptureBridge/` is superseded by the Rust crate. The macOS CI job still builds it.
Remove the `Build AudioCaptureBridge dylib` step from `.github/workflows/build.yml` once the
Linux CI job confirms the Rust path works on both platforms.

Files: `.github/workflows/build.yml`
Effort: S (30 minutes)
