# Validation Plan: Linux Support + Dictation Plugin API

**Project:** linux-dictation-plugin  
**Date:** 2026-05-09  
**Author:** Tyler Stapler  
**Status:** Ready for implementation  
**Input:** `project_plans/linux-dictation-plugin/implementation/plan.md`, `requirements.md`

---

## Test ID Convention

`[type]-[epic]-[story]-[seq]`

- Types: `UNIT`, `INTG`, `ACPT`
- Example: `UNIT-1-1-01` = first unit test for Story 1.1

---

## 1. Unit Tests

Unit tests live in `composeApp/src/desktopTest/kotlin/` and use the existing MockK + JUnit4 + `kotlin.test` stack seen in `RecordingSessionManagerTest` and `PipelineOrchestratorTest`. CommonMain-only tests live in `composeApp/src/commonTest/kotlin/`.

---

### Epic 1: Linux Runtime Baseline

#### Story 1.1 — PlatformInfo

**File:** `composeApp/src/desktopTest/kotlin/platform/PlatformInfoTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-1-1-01 | `isLinux returns true when os_name is linux` | `Platform(osName = "linux").isLinux() == true` |
| UNIT-1-1-02 | `isLinux returns true for Linux mixed case` | `Platform(osName = "Linux").isLinux() == true` |
| UNIT-1-1-03 | `isMac returns true when os_name starts with mac` | `Platform(osName = "Mac OS X").isMac() == true` |
| UNIT-1-1-04 | `isLinux and isMac are mutually exclusive` | `linux.isMac() == false && mac.isLinux() == false` |
| UNIT-1-1-05 | `isWayland returns true when WAYLAND_DISPLAY is set` | env var injected via testable constructor; `isWayland() == true` |
| UNIT-1-1-06 | `isWayland returns false when WAYLAND_DISPLAY absent` | `isWayland() == false` |
| UNIT-1-1-07 | `isX11 returns true when DISPLAY is set and WAYLAND_DISPLAY absent` | `isX11() == true` |
| UNIT-1-1-08 | `unknown OS returns false for both isLinux and isMac` | `Platform(osName = "Windows 11").isLinux() == false` |

**Implementation note:** `PlatformInfo` must accept `osName: String` and `envProvider: (String) -> String?` constructor parameters for test injection, consistent with the plan's acceptance criteria.

---

### Epic 2: SystemAudioBackend

#### Story 2.1 — SilentAudioBackend defaults

**File:** `composeApp/src/desktopTest/kotlin/audio/SilentAudioBackendTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-2-1-01 | `checkPermission returns true` | `SilentAudioBackend().checkPermission() == true` |
| UNIT-2-1-02 | `requestPermission returns true` | `SilentAudioBackend().requestPermission() == true` |
| UNIT-2-1-03 | `startCapture returns false` | `SilentAudioBackend().startCapture(16000) == false` |
| UNIT-2-1-04 | `readBuffer returns 0` | `SilentAudioBackend().readBuffer(FloatArray(1024)) == 0` |
| UNIT-2-1-05 | `isAvailable returns false` | `SilentAudioBackend().isAvailable() == false` |

#### Story 2.3 — RecordingSessionManager backend injection

**File:** `composeApp/src/desktopTest/kotlin/audio/RecordingSessionManagerBackendTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-2-3-01 | `startCapture is called on injected backend when recording starts` | `verify { mockBackend.startCapture(any()) }` |
| UNIT-2-3-02 | `stopCapture is called on backend when recording stops` | `verify { mockBackend.stopCapture() }` |
| UNIT-2-3-03 | `manager falls back to silence when startCapture returns false` | WAV file is still written; no exception thrown |
| UNIT-2-3-04 | `no ScreenCaptureJniBridge import in RecordingSessionManager` | Compilation check: class does not reference JNI bridge directly |
| UNIT-2-3-05 | `SilentAudioBackend produces valid stereo WAV` | Equivalent to existing `UNIT-S6-04`; now parameterized with `SilentAudioBackend` explicitly |

#### Story 2.6 — SystemAudioBackendFactory

**File:** `composeApp/src/desktopTest/kotlin/audio/SystemAudioBackendFactoryTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-2-6-01 | `create returns ScreenCaptureBackend on macOS` | factory called with injected `PlatformInfo(osName="Mac OS X")`; result `is ScreenCaptureBackend` |
| UNIT-2-6-02 | `create returns PipeWireCaptureBackend on Linux` | result `is PipeWireCaptureBackend` |
| UNIT-2-6-03 | `create returns SilentAudioBackend on unknown OS` | result `is SilentAudioBackend` |

**Implementation note:** `SystemAudioBackendFactory` must accept a `platformInfo: PlatformInfo` parameter (or use the testable singleton) so tests can inject platform identity without altering `System.getProperty`.

#### Story 2.5 — PipeWireCaptureBackend availability guard

**File:** `composeApp/src/desktopTest/kotlin/audio/PipeWireCaptureBackendTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-2-5-01 | `isAvailable returns false on macOS CI (not Linux)` | `PipeWireCaptureBackend(platform = macPlatform).isAvailable() == false` |
| UNIT-2-5-02 | `isAvailable returns false when .so resource absent` | mock resource loader returns null; `isAvailable() == false`; no exception |

---

### Epic 3: Plugin Loading Infrastructure

#### Story 3.1 — DictationMode serialization

**File:** `composeApp/src/commonTest/kotlin/domain/plugin/DictationModeTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-3-1-01 | `DictationMode values round-trip through JSON` | `Json.decodeFromString<DictationMode>(Json.encodeToString(PUSH_TO_TALK)) == PUSH_TO_TALK` |
| UNIT-3-1-02 | `all three modes survive serialization` | All of `PUSH_TO_TALK`, `FILE_TRANSCRIPTION`, `LIVE_CAPTIONS` round-trip |

#### Story 3.3 — PluginLoader isolation

**File:** `composeApp/src/desktopTest/kotlin/plugin/PluginLoaderTest.kt`  
**Fixture:** `composeApp/src/desktopTest/resources/testplugin.jar` (a minimal SpeechOutputPlugin implementation)  
**Fixture:** `composeApp/src/desktopTest/resources/crashingplugin.jar` (throws RuntimeException in constructor)

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-3-3-01 | `loadAll discovers plugin from META-INF/services via ServiceLoader` | result contains `PluginLoadResult.Success` with correct `plugin.id` |
| UNIT-3-3-02 | `each JAR gets its own URLClassLoader` | two plugins loaded; their classloaders are different instances |
| UNIT-3-3-03 | `crashing plugin returns Failure without affecting other plugins` | `crashingplugin.jar` → `PluginLoadResult.Failure`; valid plugin still loaded successfully in same call |
| UNIT-3-3-04 | `unload calls plugin.close() then classLoader.close()` | mock plugin; `verify { plugin.close() }` called before `classLoader.close()` |
| UNIT-3-3-05 | `loadAll on empty directory returns empty list` | `PluginLoader.loadAll(emptyDir) == emptyList()` |
| UNIT-3-3-06 | `loadAll on non-existent directory returns empty list` | no exception; empty list returned |

#### Story 3.4 — AppSettings migration

**File:** `composeApp/src/commonTest/kotlin/domain/AppSettingsTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-3-4-01 | `enabledPlugins defaults to emptyMap when field absent in JSON` | `Json.decodeFromString<AppSettings>("{}")` → `enabledPlugins == emptyMap()` |
| UNIT-3-4-02 | `enabledPlugins round-trips through JSON` | map with two entries encodes and decodes to identical map |
| UNIT-3-4-03 | `old AppSettings JSON (no enabledPlugins field) does not throw` | deserialization succeeds without exception |

---

### Epic 4: TextInjector Abstraction

#### Story 4.2 — YdotoolTextInjector health-check states

**File:** `composeApp/src/desktopTest/kotlin/injection/YdotoolTextInjectorTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-4-2-01 | `checkStatus returns NOT_INSTALLED when which ydotool exits non-zero` | mock `ProcessBuilderFactory` returns exit 1 for `which`; `checkStatus() == NOT_INSTALLED` |
| UNIT-4-2-02 | `checkStatus returns DAEMON_NOT_RUNNING when ydotool installed but pgrep fails` | `which` exits 0; `pgrep -x ydotoold` exits 1 and socket absent; `checkStatus() == DAEMON_NOT_RUNNING` |
| UNIT-4-2-03 | `checkStatus returns OK when pgrep exits 0` | `pgrep` exits 0; `checkStatus() == OK` |
| UNIT-4-2-04 | `checkStatus returns OK when socket file present even if pgrep fails` | `/tmp/.ydotool_socket` exists in mock; `checkStatus() == OK` |
| UNIT-4-2-05 | `inject strips non-printable characters before invocation` | input `"hello world"` → ProcessBuilder receives `"helloworld"` |
| UNIT-4-2-06 | `inject strips null bytes` | input with embedded ` ` → cleaned string passed to process |
| UNIT-4-2-07 | `inject uses ProcessBuilder varargs form not shell string` | ProcessBuilder constructed with `["ydotool", "type", "--", text]` — no shell metacharacter expansion |
| UNIT-4-2-08 | `inject returns Result.failure when status is NOT_INSTALLED` | `inject("hello").isFailure == true` |
| UNIT-4-2-09 | `isAvailable returns false when NOT_INSTALLED` | `isAvailable() == false` |
| UNIT-4-2-10 | `isAvailable returns false when DAEMON_NOT_RUNNING` | `isAvailable() == false` |
| UNIT-4-2-11 | `isAvailable returns true when OK` | `isAvailable() == true` |

#### Story 4.3 — XdotoolTextInjector fallback

**File:** `composeApp/src/desktopTest/kotlin/injection/XdotoolTextInjectorTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-4-3-01 | `isAvailable returns false on pure Wayland (WAYLAND_DISPLAY set, DISPLAY absent)` | env injected; `isAvailable() == false` |
| UNIT-4-3-02 | `isAvailable returns true on X11 (DISPLAY set, WAYLAND_DISPLAY absent)` | `isAvailable() == true` (and `which xdotool` mocked to exit 0) |
| UNIT-4-3-03 | `isAvailable returns true under XWayland (both WAYLAND_DISPLAY and DISPLAY set)` | `isAvailable() == true` |
| UNIT-4-3-04 | `isAvailable returns false when xdotool not installed` | `which xdotool` exits 1; `isAvailable() == false` |
| UNIT-4-3-05 | `inject passes text with --clearmodifiers and -- separator` | ProcessBuilder args = `["xdotool", "type", "--clearmodifiers", "--", text]` |
| UNIT-4-3-06 | `inject applies same text sanitization as YdotoolTextInjector` | non-printable chars stripped |

#### Story 4.4 — AutoDetectTextInjector selection logic

**File:** `composeApp/src/desktopTest/kotlin/injection/AutoDetectTextInjectorTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-4-4-01 | `selects first available candidate (ydotool)` | mock ydotool `isAvailable=true`; mock xdotool `isAvailable=true`; selected = ydotool |
| UNIT-4-4-02 | `falls back to xdotool when ydotool unavailable` | mock ydotool `isAvailable=false`; mock xdotool `isAvailable=true`; `inject` delegates to xdotool mock |
| UNIT-4-4-03 | `inject returns failure when no candidate available` | both `isAvailable=false`; `inject("hi").isFailure == true`; exception is `TextInjectorUnavailableException` |
| UNIT-4-4-04 | `caches selection across multiple inject calls` | `isAvailable()` called at most once per candidate per session |
| UNIT-4-4-05 | `isAvailable returns false when candidate list is empty` | `AutoDetectTextInjector(emptyList()).isAvailable() == false` |

---

### Epic 5: Dictation Plugin

#### Story 5.1 — DictationPlugin shell

**File:** `composeApp/src/desktopTest/kotlin/plugin/dictation/DictationPluginTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-5-1-01 | `id equals com.agrapha.dictation` | `DictationPlugin().id == "com.agrapha.dictation"` |
| UNIT-5-1-02 | `name equals Dictation` | `DictationPlugin().name == "Dictation"` |
| UNIT-5-1-03 | `supportedModes contains all three DictationMode values` | set equals `{PUSH_TO_TALK, FILE_TRANSCRIPTION, LIVE_CAPTIONS}` |
| UNIT-5-1-04 | `activate with unimplemented mode throws UnsupportedOperationException` | in shell state before 5.2–5.4 are wired |
| UNIT-5-1-05 | `close is idempotent — calling twice does not throw` | `plugin.close(); plugin.close()` — no exception |

#### Story 5.2 — PUSH_TO_TALK lifecycle (mocked dependencies)

**File:** `composeApp/src/desktopTest/kotlin/plugin/dictation/PushToTalkModeTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-5-2-01 | `activate PUSH_TO_TALK calls MicCaptureService.start on hotkey press` | mock `MicCaptureService`; simulate hotkey event; `verify { micService.startCapture() }` |
| UNIT-5-2-02 | `hotkey release stops mic capture and calls WhisperService.transcribe` | `verify { whisperService.transcribe(any()) }` |
| UNIT-5-2-03 | `transcribed text is passed to TextInjector.inject` | mock `WhisperService` returns "hello world"; `verify { injector.inject("hello world") }` |
| UNIT-5-2-04 | `deactivate stops mic capture and unregisters hotkey` | `verify { micService.stopCapture() }` after `deactivate()` |
| UNIT-5-2-05 | `activate logs PluginException when GlobalHotkeyProvider unavailable` | mock provider throws; exception caught; no crash; `PluginException` message logged |

#### Story 5.3 — FILE_TRANSCRIPTION mode

**File:** `composeApp/src/desktopTest/kotlin/plugin/dictation/FileTranscriptionModeTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-5-3-01 | `activate FILE_TRANSCRIPTION writes transcript to outputPath` | mock `WhisperService` returns fixed segments; output file contains transcript text |
| UNIT-5-3-02 | `activate returns PluginException when inputPath missing from config` | `config = emptyMap()`; `activate` does not throw; returns/logs `PluginException` |
| UNIT-5-3-03 | `activate returns PluginException when input file not found` | `config["inputPath"] = "/nonexistent.wav"`; error surfaced, no crash |
| UNIT-5-3-04 | `transcript written to stdout when outputPath absent` | no `outputPath` in config; mock WhisperService; stdout capture contains transcript |

#### Story 5.5 — ServiceLoader registration

**File:** `composeApp/src/desktopTest/kotlin/plugin/ServiceLoaderRegistrationTest.kt`

| ID | Test name | Assertion |
|----|-----------|-----------|
| UNIT-5-5-01 | `ServiceLoader finds DictationPlugin via META-INF/services file` | `ServiceLoader.load(SpeechOutputPlugin::class.java).toList()` contains a `DictationPlugin` instance |
| UNIT-5-5-02 | `discovered plugin has correct id` | discovered plugin `.id == "com.agrapha.dictation"` |

---

### Unit Test Summary

| Epic | Count |
|------|-------|
| Epic 1 (PlatformInfo) | 8 |
| Epic 2 (SystemAudioBackend) | 13 |
| Epic 3 (Plugin loading) | 11 |
| Epic 4 (TextInjector) | 22 |
| Epic 5 (DictationPlugin) | 16 |
| **Total unit tests** | **70** |

---

## 2. Integration Tests

Integration tests require a JVM with access to real filesystem paths but no actual hardware. They live in `composeApp/src/desktopTest/kotlin/integration/` and are tagged `@Category(IntegrationTest::class)` to allow selective execution.

---

### US-01: Linux mic capture — headless CI safety

**File:** `composeApp/src/desktopTest/kotlin/integration/MicCaptureServiceIntegrationTest.kt`

| ID | Test name | Description | Assertion |
|----|-----------|-------------|-----------|
| INTG-01-01 | `MicCaptureService does not crash when no audio device available` | Run `MicCaptureService` under `AudioSystem` with no real device (CI headless); verify graceful degradation | No exception propagated; `captureFlow()` emits silence or terminates cleanly |
| INTG-01-02 | `MicCaptureService produces valid PCM frames on null audio source` | Swap in a `NullTargetDataLine` implementing `TargetDataLine`; call start/stop | WAV byte structure valid; no IOOBE |

**Mock strategy:** Implement `NullTargetDataLine` that returns zeros for `read()` calls. Pass it to a `MicCaptureService` constructor overload accepting `TargetDataLine`.

---

### US-02: PipeWireCaptureBackend — socket absence

**File:** `composeApp/src/desktopTest/kotlin/integration/PipeWireCaptureBackendIntegrationTest.kt`

| ID | Test name | Description | Assertion |
|----|-----------|-------------|-----------|
| INTG-02-01 | `isAvailable returns false when XDG_RUNTIME_DIR/pipewire-0 socket absent` | Set `XDG_RUNTIME_DIR` to a temp dir with no `pipewire-0` socket via injected env provider | `PipeWireCaptureBackend.isAvailable() == false` |
| INTG-02-02 | `RecordingSessionManager gracefully falls back to SilentAudioBackend when PipeWire unavailable` | Inject `PipeWireCaptureBackend` (socket absent) into `RecordingSessionManager`; start + stop recording | WAV file produced; no crash; audio channel 1 is silent (all zero samples) |
| INTG-02-03 | `RecordingSessionManager isAvailable check is called before startCapture` | Mock backend; call `startRecording`; assert `isAvailable()` checked first (ordering) | `verify(ordering = ORDERED) { backend.isAvailable(); backend.startCapture(any()) }` — or `isAvailable()` gates `startCapture` call |

---

### US-04: Push-to-talk roundtrip (fully mocked pipeline)

**File:** `composeApp/src/desktopTest/kotlin/integration/PushToTalkRoundtripTest.kt`

| ID | Test name | Description | Assertion |
|----|-----------|-------------|-----------|
| INTG-04-01 | `inject call receives transcribed text after mock hotkey trigger` | Wire `DictationPlugin(PUSH_TO_TALK)` with mock `MicCaptureService`, mock `WhisperService` (returns "hello world"), mock `TextInjector`; fire synthetic hotkey press + release | `verify { injector.inject("hello world") }` |
| INTG-04-02 | `FILE_TRANSCRIPTION mode writes transcript given WAV fixture` | Provide a minimal valid WAV fixture at `src/desktopTest/resources/fixtures/hello.wav`; mock `WhisperService` returns `[TranscriptSegment("hello world")]`; call `activate(FILE_TRANSCRIPTION, mapOf("inputPath" to fixturePath, "outputPath" to tempOut))` | `tempOut` file contains "hello world" |
| INTG-04-03 | `FILE_TRANSCRIPTION mode output file is created even if outputPath parent dir exists` | Same as above with nested temp path | File exists, no exception |

---

### US-03: Plugin isolation under crash

**File:** `composeApp/src/desktopTest/kotlin/integration/PluginIsolationIntegrationTest.kt`

| ID | Test name | Description | Assertion |
|----|-----------|-------------|-----------|
| INTG-03-01 | `PluginLoader continues loading remaining plugins when one throws RuntimeException from activate` | Load `crashingplugin.jar` (throws in `activate`) and `testplugin.jar`; call `activate` on all `Success` results | Crashing plugin `activate` returns `Failure`; good plugin still usable |
| INTG-03-02 | `PluginLoader.unload closes URLClassLoader` | Load test JAR; call `unload(pluginId)`; attempt to load a class from the closed classloader | `IllegalStateException` or `IOException` thrown by the now-closed `URLClassLoader` |
| INTG-03-03 | `ServiceLoader discovers DictationPlugin as built-in without external JAR` | Call `ServiceLoader.load` on the classpath with no external plugins dir | `DictationPlugin` found with `id == "com.agrapha.dictation"` |

---

### Integration Test Summary

| User story | Count |
|------------|-------|
| US-01 (Linux mic capture) | 2 |
| US-02 (PipeWire fallback) | 3 |
| US-03 (Plugin isolation) | 3 |
| US-04 (Push-to-talk roundtrip) | 3 |
| **Total integration tests** | **11** |

---

## 3. Acceptance Tests (Manual, Documented)

These tests require a physical Linux desktop with audio hardware and compositor. They are run manually before each release and documented in the release checklist.

---

### ACPT-01: Fresh Ubuntu 22.04 + PipeWire — dual-channel transcript

**Requirement:** US-02

**Setup:**
1. Fresh Ubuntu 22.04 LTS with PipeWire and `pipewire-pulse` installed.
2. Agrapha built from source: `./gradlew :composeApp:run`.
3. A test call on a softphone (e.g., SIP client) with loopback audio enabled via `pw-loopback`.

**Steps:**
1. Launch Agrapha.
2. Start a recording session.
3. Speak into the microphone for 30 seconds; simultaneously play back a 30-second audio clip via the system speaker (captured via PipeWire monitor source).
4. Stop recording; wait for Whisper transcription.

**Pass criteria:**
- Resulting WAV is stereo (2-channel, verified by `ffprobe`).
- Channel 0 contains mic audio (voice visible in waveform).
- Channel 1 contains system audio (playback visible in waveform).
- Transcript contains segments from both sources with distinct speaker labels.
- No crash or unhandled exception in logs.

---

### ACPT-02: Wayland + ydotool — push-to-talk text injection

**Requirement:** US-04

**Setup:**
1. GNOME/KDE Plasma on Wayland; `ydotool` installed; `ydotoold` running as a user service.
2. Agrapha running with `DictationPlugin` enabled in Settings.
3. A text editor (e.g., gedit) open and focused.

**Steps:**
1. Hold `Super+Space` (or configured hotkey).
2. Speak a 5-word phrase clearly.
3. Release hotkey.
4. Observe the text editor.

**Pass criteria:**
- Whisper transcript appears in the focused text editor within 1.5 seconds of hotkey release.
- Text is correctly injected at cursor position.
- No extra characters, null bytes, or shell metacharacters injected.
- Application log shows `YdotoolTextInjector selected`.

---

### ACPT-03: X11 + xdotool fallback — ydotoold not running

**Requirement:** US-05

**Setup:**
1. X11 session (or XWayland with `DISPLAY` set); `xdotool` installed; `ydotoold` NOT running and socket absent.
2. Agrapha running with `DictationPlugin` enabled.
3. Text editor focused.

**Steps:**
1. Hold and release push-to-talk hotkey; speak phrase.

**Pass criteria:**
- Application log shows `YdotoolTextInjector status: DAEMON_NOT_RUNNING; falling back to XdotoolTextInjector`.
- `XdotoolTextInjector selected` appears in log.
- Text injected correctly into focused window.
- No crash; `TextInjectorUnavailableException` not raised.

---

### ACPT-04: Plugin JAR drop-in — third-party plugin loads in Settings

**Requirement:** US-03

**Setup:**
1. Agrapha not running.
2. A third-party JAR implementing `SpeechOutputPlugin` placed at `~/.config/agrapha/plugins/myplugin.jar`.

**Steps:**
1. Launch Agrapha.
2. Open Settings → Plugins.

**Pass criteria:**
- Plugin name appears in the Plugins section with an enable/disable toggle.
- Enable toggle persists across app restart.
- Plugin `activate()` is called when recording starts with the plugin enabled.
- Disabling the plugin calls `PluginLoader.unload(pluginId)` (verify via log: `Plugin unloaded: <id>`).
- Removing the JAR and restarting shows an empty plugins section (no crash).

---

### ACPT-05: Linux mic-only recording — no PipeWire

**Requirement:** US-01

**Setup:**
1. Ubuntu 22.04 with PulseAudio (no PipeWire), or PipeWire socket deliberately absent.
2. Microphone attached.

**Steps:**
1. Launch Agrapha.
2. Start a mic recording session.
3. Speak for 30 seconds; stop.
4. Observe transcript and Logseq export.

**Pass criteria:**
- App starts without crash or permission dialog.
- System audio channel is silent (graceful fallback: `SilentAudioBackend`).
- Mic channel captures audio correctly.
- Whisper transcript is non-empty.
- Logseq journal entry written to configured path.
- Settings does not show a PipeWire error — only a subtle status indicator.

---

### Acceptance Test Summary

| ID | Description | Requirement |
|----|-------------|-------------|
| ACPT-01 | Dual-channel transcript on PipeWire | US-02 |
| ACPT-02 | Push-to-talk + ydotool on Wayland | US-04 |
| ACPT-03 | xdotool fallback on X11 | US-05 |
| ACPT-04 | Plugin JAR drop-in via Settings | US-03 |
| ACPT-05 | Mic-only recording without PipeWire | US-01 |
| **Total** | | **5** |

---

## 4. Requirement-to-Test Traceability Matrix

| Requirement | Description | Unit Tests | Integration Tests | Acceptance Tests |
|-------------|-------------|------------|-------------------|------------------|
| **US-01** | Linux mic recording (no macOS dependency) | UNIT-1-1-01 through 08 (PlatformInfo), UNIT-2-3-01 through 05 (RSM injection) | INTG-01-01, INTG-01-02 | ACPT-01, ACPT-05 |
| **US-02** | Linux system audio via PipeWire | UNIT-2-5-01, UNIT-2-5-02, UNIT-2-6-01 through 03, UNIT-2-1-01 through 05 | INTG-02-01, INTG-02-02, INTG-02-03 | ACPT-01 |
| **US-03** | Plugin loading infrastructure | UNIT-3-1-01, UNIT-3-1-02, UNIT-3-3-01 through 06, UNIT-3-4-01 through 03, UNIT-5-5-01, UNIT-5-5-02 | INTG-03-01, INTG-03-02, INTG-03-03 | ACPT-04 |
| **US-04** | Push-to-talk dictation plugin | UNIT-5-1-01 through 05, UNIT-5-2-01 through 05, UNIT-5-3-01 through 04 | INTG-04-01, INTG-04-02, INTG-04-03 | ACPT-02 |
| **US-05** | TextInjector abstraction | UNIT-4-2-01 through 11, UNIT-4-3-01 through 06, UNIT-4-4-01 through 05 | (covered by INTG-04-01) | ACPT-03 |

**Coverage:** 5 / 5 user stories covered (100%).

### Acceptance Criteria Traceability (per story)

| Story AC | Tests covering it |
|----------|-------------------|
| 1.1: `isLinux()` case-insensitive | UNIT-1-1-01, UNIT-1-1-02 |
| 1.1: `isMac()` prefix match | UNIT-1-1-03 |
| 1.1: `isWayland()` env var check | UNIT-1-1-05, UNIT-1-1-06 |
| 2.1: `SilentAudioBackend` safe defaults | UNIT-2-1-01 through 05 |
| 2.3: `RecordingSessionManager` backend injection | UNIT-2-3-01 through 05 |
| 2.5: `isAvailable()` false on non-Linux / missing .so | UNIT-2-5-01, UNIT-2-5-02 |
| 2.6: Factory returns correct backend per OS | UNIT-2-6-01 through 03 |
| 3.3: `PluginLoadResult.Failure` on crash; isolation | UNIT-3-3-03, INTG-03-01 |
| 3.3: `URLClassLoader.close()` on unload | UNIT-3-3-04, INTG-03-02 |
| 3.4: Missing `enabledPlugins` field defaults to empty map | UNIT-3-4-01, UNIT-3-4-03 |
| 4.2: `YdotoolStatus` three-state enum | UNIT-4-2-01 through 04 |
| 4.2: Text sanitization | UNIT-4-2-05, UNIT-4-2-06 |
| 4.2: `ProcessBuilder` varargs (no shell injection) | UNIT-4-2-07 |
| 4.3: Pure Wayland guard | UNIT-4-3-01 |
| 4.3: XWayland allowed | UNIT-4-3-03 |
| 4.4: Detection order ydotool → xdotool | UNIT-4-4-01, UNIT-4-4-02 |
| 4.4: `TextInjectorUnavailableException` when neither available | UNIT-4-4-03 |
| 5.1: Plugin identity fields | UNIT-5-1-01 through 03 |
| 5.2: Full PUSH_TO_TALK call sequence | UNIT-5-2-01 through 04, INTG-04-01 |
| 5.3: FILE_TRANSCRIPTION output | UNIT-5-3-01, INTG-04-02 |
| 5.5: `ServiceLoader` discovers `DictationPlugin` | UNIT-5-5-01, UNIT-5-5-02, INTG-03-03 |
| US-02: Graceful fallback to silent channel | INTG-02-02, ACPT-05 |
| US-04: Injection latency < 1.5s for 5-word utterance | ACPT-02 (manual timing) |
| US-03: Crashing plugin does not crash main app | UNIT-3-3-03, INTG-03-01 |

---

## 5. CI Configuration Notes

### Platform matrix

| Test suite | Runs on | Notes |
|------------|---------|-------|
| Unit tests (`desktopTest`) | `ubuntu-latest` + `macos-latest` | Both runners; parallel jobs |
| Common unit tests (`commonTest`) | `ubuntu-latest` + `macos-latest` | Cross-platform by definition |
| Integration tests (`@Category(IntegrationTest::class)`) | `ubuntu-latest` only | Linux-specific socket/env tests |
| Acceptance tests | Manual (physical Linux desktop) | Not automated in CI |

### Linux-only tests

The following tests must only run on the Linux CI job (`build-linux`) because they depend on Linux-specific socket paths or platform detection:

- `INTG-02-01` — checks `$XDG_RUNTIME_DIR/pipewire-0` path
- `INTG-02-02`, `INTG-02-03` — `PipeWireCaptureBackend` behavior
- `UNIT-2-5-01`, `UNIT-2-5-02` — `isAvailable()` false on non-Linux CI (these are safe on macOS too, but should also pass on Linux with socket absent)

### macOS-only tests

- `UNIT-2-6-01` — `SystemAudioBackendFactory` returns `ScreenCaptureBackend` on macOS: this test must pass on the macOS CI runner and is expected to fail (wrong type) on Linux. Use `assumeTrue(Platform.isMac())` as a guard.

### Subprocess mock strategy for ydotool / xdotool in CI

Neither `ydotool` nor `xdotool` is installed on GitHub-hosted runners by default. All `YdotoolTextInjector` and `XdotoolTextInjector` unit tests use a `ProcessBuilderFactory` interface injected via the constructor:

```kotlin
interface ProcessBuilderFactory {
    fun create(vararg command: String): ProcessBuilder
}
```

- **Production:** `DefaultProcessBuilderFactory` calls `ProcessBuilder(*command)`.
- **Test:** `FakeProcessBuilderFactory` returns a pre-configured `Process` stub with a configurable exit code and stdout.

This pattern avoids installing system tools in CI and makes subprocess behavior fully deterministic. It mirrors the approach implied by Story 4.2's acceptance criteria ("Unit tests mock the subprocess with a test `ProcessBuilder` factory; no actual `ydotool` required in CI").

For `which` checks specifically, the `FakeProcessBuilderFactory` matches the first argument: if `command[0] == "which"`, return the configured `whichExitCode`; otherwise return the configured `injectExitCode`.

### GitHub Actions job structure

```yaml
jobs:
  test-macos:
    runs-on: macos-latest
    steps:
      - ./gradlew :composeApp:desktopTest
      # Runs all unit tests; skips Linux-only integration tests via @Category guard

  test-linux:
    runs-on: ubuntu-latest
    steps:
      - sudo apt-get install -y libpipewire-0.3-dev libspa-0.2-dev xvfb
      # xvfb-run for any AWT-dependent test setup
      - xvfb-run ./gradlew :composeApp:desktopTest :composeApp:integrationTest
      # Runs all unit + integration tests
```

### Test fixture requirements

| Fixture | Location | Purpose |
|---------|----------|---------|
| `testplugin.jar` | `composeApp/src/desktopTest/resources/` | Valid `SpeechOutputPlugin` for PluginLoader tests |
| `crashingplugin.jar` | `composeApp/src/desktopTest/resources/` | Plugin that throws in constructor for isolation tests |
| `hello.wav` | `composeApp/src/desktopTest/resources/fixtures/` | Minimal valid WAV for FILE_TRANSCRIPTION mode tests |

`testplugin.jar` and `crashingplugin.jar` should be pre-compiled and committed. A `testplugins/` sub-project in the Gradle build can produce them as part of `composeApp:testClasses`. Alternatively, they can be hand-crafted minimal JARs (< 2 KB) and committed as binary test fixtures, consistent with `gradle-wrapper.jar` already being committed.

---

## Test Count Summary

| Type | Count |
|------|-------|
| Unit tests | 70 |
| Integration tests | 11 |
| Acceptance tests (manual) | 5 |
| **Total** | **86** |

**Requirements coverage:** 5 / 5 user stories (100%)

**Story-level AC coverage:** 23 out of 23 documented acceptance criteria have at least one automated test or manual acceptance test mapping.
