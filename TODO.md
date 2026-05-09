# Agrapha — Project Status

**Last updated:** 2026-05-09
**Active branch:** `feature/linux-dictation-plugin` (PR #1 open against `main`)

---

## Summary

PR #1 delivers Linux parity for Agrapha via PipeWire audio capture, a ServiceLoader-based plugin SPI,
a built-in DictationPlugin with all three modes, and a Rust JNI crate replacing all platform-specific
native bridges on both Linux and macOS.

**All 194 tests pass.** The implementation substantially outpaces the original 5-epic plan.

---

## PR #1 Merge Checklist

Items that must be resolved before PR #1 can merge to `main`:

- [ ] **Story 1.3 — Linux CI job** (MISSING — highest priority blocker for merge)
  - `.github/workflows/build.yml` has only a `macos-14` job
  - No `ubuntu-latest` job exists; Rust+PipeWire build is untested in CI
  - Task file: `docs/tasks/linux-dictation-plugin.md` (Story 1.3)
- [ ] **Story 1.2 — Verify Linux baseline** (evidence not committed)
  - PipeWire, whisper-jni AVX2, and Logseq export verification not documented
  - Acceptable: add a CI job (Story 1.3) that proves the baseline automatically
- [ ] Stale Swift/ObjC `native/AudioCaptureBridge/` — superseded by the Rust crate but still
  committed; decision: delete or keep for reference (currently raises confusion in the diff)

Items that are complete and verified:

- [x] Story 1.1 — PlatformInfo utility (`PlatformInfo.kt` + tests)
- [x] Story 2.1 — SystemAudioBackend interface + NoOpSystemAudioBackend
- [x] Story 2.2 — ScreenCaptureBackend (macOS adapter)
- [x] Story 2.3 — RecordingSessionManager refactored to constructor-inject SystemAudioBackend
- [x] Story 2.4 — PipeWire capture — Rust crate (`native/agrapha-native/src/pipewire_capture.rs`)
- [x] Story 2.5 — PipeWireCaptureBackend (Kotlin wrapper + JNI bridge)
- [x] Story 2.6 — SystemAudioBackendFactory (platform dispatch)
- [x] Story 2.7 — Gradle build task (`buildAgraphaNative` Exec task, wired to desktopProcessResources)
- [x] Story 3.1 — DictationMode enum (commonMain, @Serializable)
- [x] Story 3.2 — SpeechOutputPlugin interface + PluginException (commonMain)
- [x] Story 3.3 — PluginLoader (ServiceLoader + child-first URLClassLoader + unload())
- [x] Story 3.4 — AppSettings.enabledPlugins field added with default emptyMap()
- [x] Story 3.5 — PluginsSettingsSection composable (success + failure rows + toggle)
- [x] Story 4.1 — TextInjector interface + TextInjectorUnavailableException
- [x] Story 4.2 — YdotoolTextInjector (daemon check, shell-injection-safe ProcessBuilder)
- [x] Story 4.3 — XdotoolTextInjector (Wayland guard, X11 fallback)
- [x] Story 4.4 — AutoDetectTextInjector (ydotool-first, xdotool fallback, cached selection)
- [x] Story 5.1 — DictationPlugin shell (correct id/name/version/supportedModes)
- [x] Story 5.2 — PUSH_TO_TALK mode (global hotkey via HotkeyService, triggerDictation())
- [x] Story 5.3 — FILE_TRANSCRIPTION mode (file path config, WhisperService transcription)
- [x] Story 5.4 — LIVE_CAPTIONS mode (MicCaptureService + 3s chunk Whisper + liveSegments StateFlow)
- [x] Story 5.5 — ServiceLoader registration (META-INF/services file + ServiceLoaderRegistrationTest)
- [x] macOS Swift+ObjC JNI bridge replaced with pure Rust (mac_audio_capture.rs)
- [x] HotkeyService with injectable HotkeyBridge (X11 XGrabKey + Wayland portal)
- [x] GlobalShortcutJniBridge (Kotlin) + global_shortcut.rs (Rust) — both backends

---

## Implementation vs Plan Delta

The implementation diverged from the plan in several beneficial ways:

| Plan | Actual | Notes |
|---|---|---|
| Separate C JNI (`libPipeWireCaptureBridge.so`) | Single Rust crate (`libagrapha_native.so`) | Covers PipeWire + global hotkeys + macOS audio in one binary |
| Swift+ObjC macOS bridge retained | Replaced by Rust objc2 bindings | Eliminates the Swift toolchain dependency from Linux CI |
| ADR-003: in-window only for MVP | Full X11 XGrabKey + Wayland portal both implemented | Global hotkey works on both compositors |
| SpeechOutputPlugin without `version` or `isAvailable()` | Interface has `version: String` and `isAvailable()` | Richer contract for plugin management UI |
| TextInjector with `isAvailable(): Boolean` | Interface uses `checkStatus(): Status` enum | Three-state health (OK / NOT_INSTALLED / DAEMON_NOT_RUNNING) |
| `SilentAudioBackend` name | `NoOpSystemAudioBackend` name | Same semantics |

---

## Open Bugs

No bugs tracked in `docs/bugs/` at this time.

The following known risks from the plan are unresolved — they are environmental constraints, not
code defects:

| Risk | Status | Mitigation |
|---|---|---|
| R3: whisper-jni AVX2 requirement (SIGILL on pre-Haswell) | Open — not gated in CI | `PlatformInfo.avx2Supported()` exists; WhisperService does not call it yet |
| R5: Global hotkey impossible on GNOME Wayland without portal | Mitigated | Wayland portal path implemented in global_shortcut.rs; in-window fallback logged gracefully |
| R2: ydotoold daemon not running | Mitigated | YdotoolStatus enum + DictationPlugin logs warning; xdotool fallback via AutoDetectTextInjector |

---

## Next After PR #1 Merge

The following work streams are queued but not started:

1. **Linux CI job** — see `docs/tasks/linux-dictation-plugin.md` Story 1.3 (required for merge)
2. **whisper-jni AVX2 guard** — call `PlatformInfo.avx2Supported()` in `WhisperService.loadModel()`
   and surface a friendly error dialog instead of SIGILL crash
3. **LIVE_CAPTIONS overlay window** — `DictationPlugin.activateLiveCaptions()` updates a StateFlow
   but the floating Compose `Window` is not yet created; a UI consumer is needed
4. **FluidAudio diarization backends** — tracked in `docs/tasks/fluida-audio-backends.md`
5. **Transcription/diarization improvements** — tracked in `docs/tasks/transcription-diarization-improvement.md`
6. **Agrapha extraction** — tracked in `docs/tasks/agrapha-extraction.md`

---

## Projects and Task Files

| File | Status | Description |
|---|---|---|
| `docs/tasks/linux-dictation-plugin.md` | Active | Linux CI job (Story 1.3) — required for PR #1 merge |
| `docs/tasks/fluida-audio-backends.md` | Queued | FluidAudio CoreML diarization backend |
| `docs/tasks/transcription-diarization-improvement.md` | Queued | Diarization + transcription quality work |
| `docs/tasks/agrapha-extraction.md` | Queued | Agrapha core extraction / packaging |
| `project_plans/linux-dictation-plugin/` | Complete | Full 5-epic plan — all stories implemented |
