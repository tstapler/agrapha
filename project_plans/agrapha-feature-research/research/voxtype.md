# VoxType — Research

## Summary

VoxType (712 stars, Rust, MIT) is a push-to-talk voice-to-text daemon for Linux/Wayland that holds a hotkey, records speech, and types the transcription at the cursor. It ships 7 transcription engines (Whisper + 6 ONNX models), a meeting mode with chunked recording and export to Markdown/JSON/SRT/VTT, Waybar status integration, and rich post-processing hooks. It is the closest architectural ancestor to where Agrapha could go for global dictation mode.

## Feature Inventory

- **Push-to-talk (hold) and toggle (press once)** via compositor keybindings (Hyprland, Sway, River) or evdev fallback (X11)
- **7 transcription engines** selectable at runtime via `--engine` flag or `config.toml`:
  - Whisper (whisper.cpp, 99 languages; local, CLI subprocess, or remote HTTP)
  - Parakeet (FastConformer TDT, ONNX, English)
  - Moonshine (encoder-decoder, ONNX, English, edge-optimised)
  - SenseVoice (CTC, ONNX, zh/en/ja/ko/yue)
  - Paraformer (non-autoregressive, ONNX, zh+en bilingual)
  - Dolphin (CTC E-Branchformer, ONNX, 40 languages + 22 Chinese dialects)
  - Omnilingual (wav2vec2 CTC, ONNX, 1600+ languages)
- **Meeting mode** (`voxtype meeting start/stop/export/summarize`): continuous chunked transcription, speaker attribution (ML diarization in progress on `feature/fix-ml-diarization`), export to Markdown, plain text, JSON, SRT, VTT; AI summarization via Ollama
- **Waybar/polybar integration**: live recording-state JSON via `voxtype status --follow --format json`; extended output includes model, device, backend
- **Audio feedback**: start/stop/error sound cues; three built-in themes (default, subtle, mechanical), custom WAV directory
- **Text post-processing**: word replacements (`replacements = { "vox type" = "voxtype" }`), spoken punctuation (`spoken_punctuation = true` converts "period" → ".", "open paren" → "(")
- **Post-process command hook**: pipe transcription through any stdin→stdout command (Ollama, llama.cpp, LM Studio); timeout + graceful fallback to original
- **Output fallback chain**: wtype → dotool (XKB layout support) → ydotool → clipboard
- **On-demand model loading**: model loaded only when recording, saves RAM
- **Auto-submit**: optional Enter key after transcription (for chat/terminals)
- **Remote whisper.cpp server**: HTTP API backend for LAN inference offload
- **GPU acceleration**: Vulkan (AMD/NVIDIA/Intel), CUDA, Metal (build flags), HIP/ROCm
- **Multilingual**: auto-detect or force language; translation to English
- **Paste mode**: copies to clipboard then simulates Ctrl+V (for non-US keyboard layouts)

## Architecture Notes

- **Daemon model**: single foreground process, controlled via `voxtype record start/stop/toggle` subcommands that send SIGUSR1/SIGUSR2; compositor keybindings call these subcommands directly — no elevated permissions required on Wayland
- **Engine dispatch**: `Engine` enum dispatches to Whisper (whisper-rs crate) or ONNX Runtime (custom `onnx` feature flags per engine); ONNX engines are compile-time feature flags (`--features parakeet,moonshine,sensevoice,...`)
- **Audio**: cpal for cross-platform capture; PipeWire/PulseAudio on Linux
- **Meeting mode**: chunked continuous recording loop; segments timestamped; speaker embedding similarity clustering for diarization (TitaNet/ECAPA, 81 MB model, in-progress)
- **State file**: JSON written to a predictable path for Waybar polling
- **Config**: TOML at `~/.config/voxtype/config.toml`; full annotated default at `config/default.toml`
- **Relevant to Agrapha's JVM stack**: Voxtype is Rust — not directly portable — but its engine selection pattern (enum + feature-gated backends) and meeting-mode CLI design are directly transferable as design patterns

## Agrapha Relevance

| Feature | Rationale |
|---|---|
| **Engine selection with 7 backends** | Agrapha currently hard-codes Whisper.cpp JNI. Adding a Parakeet (ONNX via ONNX Runtime for Java) or Moonshine path would let users trade accuracy for speed on lower-power Macs. The engine enum pattern is directly adoptable. |
| **Meeting mode export to SRT/VTT/JSON** | Agrapha already exports Markdown/Logseq. SRT and VTT are standard subtitle formats that third-party tools (DaVinci, Premiere, Final Cut) can ingest. JSON export enables downstream automation. |
| **Spoken punctuation post-processing** | Developers who dictate code need punctuation. A lightweight regex replacement step (say "semicolon" → ";") would improve dictation UX for technical users at near-zero cost. |
| **Post-process LLM hook** | Agrapha already has Ollama/OpenAI integration for summaries; a per-dictation grammar-correction pass (like voxtype's `[output.post_process]` command hook) is a natural extension for dictation mode. |
| **Audio feedback themes** | Start/stop beeps reduce need to watch the UI. Low effort, high UX value. |
| **Push-to-talk / toggle modes** | Agrapha is meeting-first; a global-hotkey dictation mode (push-to-talk to any app) is voxtype's primary use case and a high-demand feature for Agrapha users. |
| **Status bar integration** | Agrapha currently has no persistent menu-bar indicator of recording state. A menu-bar extra showing recording status (inspired by voxtype's Waybar JSON) would improve discoverability. |

## Attribution Note

> Push-to-talk design, engine-selection architecture, and meeting export formats (SRT/VTT/JSON) inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT).
