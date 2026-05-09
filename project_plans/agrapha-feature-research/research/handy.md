# Handy — Research

## Summary

Handy (21,364 stars, Rust/Tauri v2, MIT) is the most popular open-source local-first STT desktop app. Press a shortcut, speak, release; text appears in any app. It supports Whisper and Parakeet V3 models, Silero VAD, a persistent transcription history (SQLite), Apple Intelligence post-processing, a custom-words/dictionary feature, filler-word filtering, configurable LLM post-processing, audio feedback, a Raycast extension, and both push-to-talk and toggle modes. Its macOS support is first-class and it is the most direct architectural reference for Agrapha's dictation mode aspirations.

## Feature Inventory

- **Global keyboard shortcut**: configurable; push-to-talk (hold) or toggle (press to start/stop) — `push_to_talk: bool` in settings
- **VAD with Silero**: `SmoothedVad` over `SileroVad` — trims silence before and after speech, reducing transcription latency and hallucinations
- **Transcription engines**:
  - Whisper (whisper-rs, local whisper.cpp bindings): Small/Medium/Turbo/Large models; GPU acceleration (Metal on Apple Silicon, CUDA on NVIDIA)
  - Parakeet V3 (transcribe-rs, ONNX Runtime): CPU-optimised, FastConformer TDT; ~5× real-time on mid-range CPU; automatic language detection; no GPU required
  - GigaAM, Canary, Cohere, Moonshine, SenseVoice also present in `LoadedEngine` enum (from source code)
- **Paste method**: `PasteMethod` enum — `Direct` (rdev keyboard injection) or `CtrlV` (clipboard + Ctrl+V simulation); auto-selected by platform
- **Transcription history**: SQLite via rusqlite with rolling migrations; stores `transcription_text`, `post_processed_text`, `post_process_prompt`, `post_process_requested`, `saved` flag, `title`, timestamp; configurable `history_limit` and `recording_retention_period`; Raycast extension browses history
- **Custom words / dictionary**: `custom_words: Vec<String>` injected as Whisper initial_prompt or Parakeet custom vocabulary to bias recognition toward user-defined terms
- **Custom filler words**: `custom_filler_words: Option<Vec<String>>` — words to strip from output (e.g., "um", "uh")
- **Word correction threshold**: `word_correction_threshold: f64` — fuzzy match confidence for custom word substitution
- **LLM post-processing**: configurable providers (OpenAI-compatible endpoints); multiple named `LLMPrompt` presets selectable; triggered per-transcription or on demand; result stored separately from raw transcription; `post_process_enabled` toggle
- **Apple Intelligence post-processing**: Rust → Swift FFI (`apple_intelligence.rs`); calls `process_text_with_system_prompt_apple()` via C-compatible struct; checks availability with `is_apple_intelligence_available()`; works fully on-device using Apple's on-device LLM (no API key)
- **Audio feedback**: `audio_feedback: bool`, `audio_feedback_volume: f32`, `SoundTheme` enum; plays sound on recording start/stop
- **Model unload timeout**: `ModelUnloadTimeout` — auto-unloads model after N seconds of idle to free RAM
- **Overlay**: configurable recording indicator overlay (position, size); disabled by default on Linux
- **Autostart**: `autostart_enabled` — launch at login
- **Start hidden**: `start_hidden` — no window on launch, only tray icon
- **System tray**: tray icon with context menu; `--no-tray` flag to disable
- **CLI flags for remote control**: `handy --toggle-transcription`, `--toggle-post-process`, `--cancel`; send commands to running instance via single-instance plugin; enables compositor/hotkey-daemon integration
- **Unix signal control**: SIGUSR1 (toggle with post-process), SIGUSR2 (toggle); enables shell script / window manager integration
- **Debug mode**: Cmd+Shift+D (macOS) / Ctrl+Shift+D; verbose logging
- **Speaker muting**: `set_mute()` mutes system audio output during recording (Windows COM, Linux PipeWire/PulseAudio/ALSA, macOS AppleScript)
- **Raycast integration**: official Raycast extension — start/stop recording, browse history, manage dictionary, switch models/languages
- **Clamshell detection**: `helpers::clamshell` — detects lid-closed state on macOS (relevant for external mic selection)

## Architecture Notes

- **Tauri v2 + Rust**: frontend is React + TypeScript + Tailwind CSS; backend is Rust with Tauri commands; type-safe bridge via tauri-specta
- **Audio pipeline**: cpal (cross-platform audio I/O) → SmoothedVad (Silero) → ring buffer → transcription engine thread; idle stream timeout (30 s)
- **TranscriptionManager**: RAII `LoadingGuard` ensures model is always unloaded on error; Arc<Mutex<Option<LoadedEngine>>> shared across threads; idle watcher thread auto-unloads model after configurable timeout; Condvar-based loading serialisation prevents concurrent model loads
- **Engine enum**: `LoadedEngine` covers Whisper, Parakeet, Moonshine, MoonshineStreaming, SenseVoice, GigaAM, Canary, Cohere — all dispatched from a single manager
- **History**: SQLite with rusqlite; schema migrations via rusqlite_migration; audio recordings stored as files alongside DB; `saved` flag for user-marked favourites
- **Apple Intelligence FFI**: `extern "C"` declarations link to Swift functions compiled into the app bundle; the `AppleLLMResponse` C struct bridges ownership safely
- **Shortcut**: `rdev` for global key events; `handy_keys.rs` + `tauri_impl.rs` implement the shortcut handler state machine
- **Relevant to Agrapha**: The TranscriptionManager idle-unload pattern, the custom-words/Whisper initial_prompt trick, and the Apple Intelligence FFI approach are all directly applicable to Agrapha's Kotlin/JNI stack. The history SQLite schema (with post-processed text as a separate column) mirrors Agrapha's SQLDelight setup closely.

## Push-to-Talk vs Toggle Implementation

Settings field `push_to_talk: bool` (default `true`) controls the recording trigger mode:
- **Push-to-talk**: global shortcut key-down starts recording; key-up stops and transcribes
- **Toggle**: first key-down starts; second key-down stops and transcribes
- CLI flags `--toggle-transcription` / `--toggle-post-process` allow external toggle from compositor or Raycast without knowing current state

## Dictionary / Custom Vocabulary

`custom_words: Vec<String>` is injected into Whisper as the `initial_prompt` parameter (biases beam search toward those token sequences) and into Parakeet as a custom word list. `word_correction_threshold: f64` controls fuzzy-match post-correction. This is the standard pattern for domain-specific vocabulary (product names, technical terms, people's names) without fine-tuning.

## macOS-Specific Notes

- Metal GPU acceleration for Whisper via feature flag at build time
- Apple Intelligence FFI: on-device LLM available on M1+ Macs running macOS Sequoia 15.1+; checked at runtime before offering as provider option
- Clamshell detection for lid-closed Mac scenarios (external monitor + keyboard setups)
- AppleScript used for system audio muting on macOS
- Homebrew cask: `brew install --cask handy`

## Agrapha Relevance

| Feature | Rationale |
|---|---|
| **Custom words / dictionary** | Agrapha users transcribe recurring names, project codes, product names. Injecting a custom word list as Whisper initial_prompt is a 1-day implementation against the existing JNI bridge. High value, low effort. |
| **Apple Intelligence post-processing** | Agrapha targets macOS M1+; the same Swift FFI pattern could provide on-device LLM correction without any API key or network. Directly applicable. |
| **Transcription history with saved-favourite flag** | Agrapha has SQLDelight; adding a `transcription_history` table with `saved`, `post_processed_text`, and `post_process_prompt` columns mirrors Handy's schema exactly. Users want to find past meeting snippets. |
| **Filler word stripping** | Remove "um", "uh", "you know" post-transcription. Trivial regex; significant output quality improvement for meeting minutes. |
| **LLM post-processing with multiple named prompts** | Agrapha has one LLM path (summary). Handy's multiple named prompts (e.g., "clean grammar", "bullet points", "email") is a natural extension. |
| **Audio feedback on start/stop** | Users need eyes-free confirmation. Low effort. |
| **Toggle vs push-to-talk** | Agrapha should offer both modes; meeting users prefer toggle so they can set-and-forget; dictation users prefer push-to-talk for precision. |
| **Model idle unload** | Whisper models (1.5–3 GB) should auto-unload after idle. Handy's idle watcher + RAII guard is the reference pattern. |
| **CLI remote control flags** | `handy --toggle-transcription` enables Raycast/Alfred/Shortcuts integration without the UI. Agrapha could expose `agrapha record toggle` for the same ecosystem integration. |

## Attribution Note

> Custom-vocabulary/dictionary design, Apple Intelligence post-processing integration, and transcription history schema inspired by [Handy](https://github.com/cjpais/Handy) (MIT).
