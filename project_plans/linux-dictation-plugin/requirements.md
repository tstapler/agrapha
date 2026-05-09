# Requirements: Linux Support + Dictation Plugin API

**Project:** linux-dictation-plugin  
**Date:** 2026-05-09  
**Author:** Tyler Stapler

---

## Problem Statement

Agrapha currently works only on macOS. The core transcription pipeline (whisper-jni, javax.sound.sampled mic capture, Logseq export) is already cross-platform, but two macOS-specific blockers remain: (1) system audio capture uses ScreenCaptureKit JNI, and (2) no mechanism exists for real-time dictation. This feature delivers a Linux-capable Agrapha with a first-class plugin API for extensible speech-to-text output modes — starting with dictation.

---

## Goals

1. **Linux parity** — Run Agrapha on Linux (Wayland + X11) with mic recording, system audio via PipeWire, Whisper transcription, and Logseq export.
2. **Plugin API** — Define a `SpeechOutputPlugin` Kotlin SPI using `java.util.ServiceLoader`. Ship plugin-loading infrastructure before any specific plugin.
3. **Dictation plugin** — Implement three dictation modes as a built-in SPI plugin: push-to-talk text injection, file transcription, and continuous live captions.
4. **Text injection abstraction** — Define a `TextInjector` interface with ydotool and xdotool backends, auto-detected at runtime.

---

## Non-Goals

- Windows support (out of scope for this phase)
- macOS changes (no regressions; macOS code paths unchanged)
- Cloud/server-side transcription
- New LLM integrations
- Distribution packaging (AppImage/flatpak) — separate concern

---

## User Stories

### US-01: Linux meeting recording (mic only)
*As a Linux user, I want to record meetings using my microphone and get a Whisper transcript, so I can use Agrapha without macOS.*

**Acceptance criteria:**
- `./gradlew :composeApp:run` starts the app on a Linux desktop (Wayland or X11)
- MicCaptureService captures audio via javax.sound.sampled
- WhisperService transcribes with the CPU backend (whisper-jni's built-in libwhisper.so)
- Logseq export writes a journal entry to the configured path
- No crash or required-but-missing permission dialogs for Linux users

### US-02: Linux system audio capture via PipeWire
*As a Linux user on a call, I want Agrapha to capture both my mic and system audio (call audio), so I get a complete dual-channel transcript.*

**Acceptance criteria:**
- A `PipeWireCaptureBridge` (C library via JNI) captures the PipeWire loopback/monitor source
- `RecordingSessionManager` uses the bridge for the system audio channel on Linux (equivalent to `ScreenCaptureJniBridge` on macOS)
- Audio captured at 16kHz mono Float32 PCM matching existing channel format
- Graceful fallback to silent system channel if PipeWire is unavailable or permission denied
- Gradle build task compiles the C bridge; CI builds it on Linux runners

### US-03: Plugin loading infrastructure
*As a developer, I want to drop a JAR implementing `SpeechOutputPlugin` into the plugins directory and have Agrapha load it automatically, so I can extend the app without forking it.*

**Acceptance criteria:**
- `SpeechOutputPlugin` interface defined in commonMain with: `id: String`, `name: String`, `supportedModes: Set<DictationMode>`, `activate(mode, config)`, `deactivate()`
- `DictationMode` enum: `PUSH_TO_TALK`, `FILE_TRANSCRIPTION`, `LIVE_CAPTIONS`
- `PluginLoader` uses `java.util.ServiceLoader<SpeechOutputPlugin>` to discover plugins from a configurable plugin directory
- Loaded plugins appear in Settings UI as a list with enable/disable toggle
- Plugin errors are isolated — a crashing plugin does not take down the main app

### US-04: Push-to-talk dictation plugin
*As a Linux user, I want to hold a hotkey, speak, and have my words typed into whatever app I'm focused on, powered by Whisper.*

**Acceptance criteria:**
- Built-in `DictationPlugin` implements `SpeechOutputPlugin`
- `PUSH_TO_TALK` mode: global hotkey (configurable, default `Super+Space`) triggers short mic recording; on release, Whisper transcribes; result injected into focused window
- `TextInjector` interface with implementations: `YdotoolTextInjector`, `XdotoolTextInjector`
- Runtime auto-detection: check `which ydotool && systemctl --user is-active ydotoold` first; fall back to `xdotool`; log which was selected
- Injected text appears at cursor position within ~1s of hotkey release for a 5-word utterance
- `FILE_TRANSCRIPTION` mode: accepts a file path, runs Whisper, writes transcript to stdout or a configured output path
- `LIVE_CAPTIONS` mode: always-on mic listener; streams transcript to a floating overlay window

### US-05: Text injector abstraction
*As a developer, I want a clean `TextInjector` interface so I can add a Wayland-native injection backend later without touching dictation logic.*

**Acceptance criteria:**
- `TextInjector` interface in desktopMain: `fun inject(text: String): Result<Unit>`
- `YdotoolTextInjector`: shells out to `ydotool type --clearmodifiers -- "<text>"` 
- `XdotoolTextInjector`: shells out to `xdotool type --clearmodifiers "<text>"`
- `AutoDetectTextInjector`: tries ydotool availability check, falls back to xdotool, throws `TextInjectorUnavailableException` if neither available
- Each injector sanitizes text (escapes quotes, strips non-printable chars) before shell invocation
- Unit tests mock the subprocess so no actual ydotool/xdotool required in CI

---

## Architecture Constraints

- **Platform isolation**: macOS-specific code stays in `desktopMain` behind `expect/actual` or OS-detection; Linux code alongside it. No macOS code touched.
- **JNI pattern**: PipeWire bridge follows the exact same pattern as `AudioCaptureBridge` (C with JNI, extracted from classpath resource, Gradle Exec build task).
- **Plugin API in commonMain**: `SpeechOutputPlugin`, `DictationMode`, and `PluginLoader` interfaces in commonMain; platform-specific implementations in desktopMain.
- **No new external Kotlin dependencies**: whisper-jni already provides the CPU backend; text injection via subprocess (no additional JNI).
- **whisper-jni CPU library**: On Linux, `WhisperJNI.loadLibrary()` loads the bundled `libwhisper.so` — already supported by the library. No code change needed in WhisperService for basic Linux support.
- **Settings persistence**: Plugin enable/disable state stored in `AppSettings` as `Map<String, Boolean>` keyed by plugin ID.

---

## Technical Risks

| Risk | Severity | Mitigation |
|---|---|---|
| PipeWire monitor source requires `pipewire-pulse` or explicit permissions | High | Document setup; graceful fallback to silent channel |
| ydotoold daemon not running on fresh systems | High | Clear error message in UI + settings note; xdotool fallback |
| whisper-jni `libwhisper.so` not bundled for the user's Linux arch | High | Verify whisper-jni Maven artifact includes linux-x86_64 and linux-aarch64 JNI libs |
| Global hotkey under Wayland requires compositor cooperation | Medium | Use JNI or JNA to hook into libxkbcommon or use `xdg-portal`; document Wayland limitations |
| Plugin classloader isolation | Medium | Use URLClassLoader per plugin; define a minimal API surface in commonMain |

---

## Success Metrics

- Agrapha starts and records on a fresh Ubuntu 22.04 LTS / Fedora 40 machine in under 5 minutes of setup
- A 5-word push-to-talk dictation completes within 1.5s of hotkey release
- Dropping a third-party plugin JAR into `~/.config/agrapha/plugins/` loads it in Settings without recompilation
- All existing macOS tests pass unchanged (zero macOS regressions)

---

## Out of Scope

- macOS dictation / push-to-talk (separate feature)
- Windows audio backend
- Plugin marketplace / remote plugin registry
- Notarization / packaging for Linux distribution
