# Comparable Projects — Research

## Summary

Five additional open-source local-first STT and meeting-transcription projects were identified beyond the three seed projects. The most Agrapha-relevant are Meetily (meeting assistant closest in intent to Agrapha), OpenWhispr (macOS-native, VA + calendar integration, local diarization), and whisper-writer (four recording modes, VAD, continuous recording). All are MIT-licensed. Cloud-only tools and mobile-only apps were excluded.

---

## Project 1 — Meetily

**URL**: https://github.com/Zackriya-Solutions/meetily
**Stars**: 11,649
**Language**: Rust (backend) + Next.js/pnpm (frontend)
**License**: MIT (Community Edition; PRO commercial tier also available)
**Platform**: macOS, Windows (Linux build from source)

### Feature Inventory

- Real-time meeting transcription using Whisper or Parakeet models (no cloud)
- Speaker diarization with SortFormer (Nvidia model; real-time on-device)
- Microphone + system audio capture simultaneously with intelligent ducking and clipping prevention
- AI-powered summaries: Ollama (local), Claude, Groq, OpenRouter, or any OpenAI-compatible endpoint
- Import existing audio files; re-transcribe with different model or language (Beta)
- GPU acceleration: Apple Silicon Metal + CoreML; NVIDIA CUDA; AMD/Intel Vulkan — auto-enabled at build time
- Export to PDF, DOCX (PRO); community edition supports standard text export
- Custom summary templates (PRO)
- Auto-meeting detection (PRO)
- GDPR compliance tooling (PRO)

### Architecture Notes

- Tauri v2 (Rust) backend + Next.js frontend (similar to Handy's Tauri architecture)
- Rust whisper.cpp bindings for transcription; SortFormer ONNX for diarization
- SQLite local storage; all recordings and transcripts on-device
- macOS: CoreML acceleration path mirrors Agrapha's existing JNI + CoreML pipeline

### Agrapha Relevance

Meetily is the closest peer to Agrapha in intent (meeting minutes + summaries + local-first). Key borrowable patterns:
- **SortFormer for real-time diarization**: more accurate than pyannote.audio for live streams; ONNX-based so potentially JNI-accessible
- **Simultaneous mic + system audio capture with ducking**: Agrapha already does this via CoreAudio JNI; Meetily's ducking/clipping-prevention implementation is worth examining for improving Agrapha's audio quality
- **Re-transcribe with different model**: let users re-process saved meetings with a newer or larger model; useful when Whisper large-v3 becomes faster on newer Apple Silicon

### Attribution Note

> Re-transcription with model selection and real-time diarization design inspired by [Meetily](https://github.com/Zackriya-Solutions/meetily) (MIT).

---

## Project 2 — OpenWhispr

**URL**: https://github.com/OpenWhispr/openwhispr
**Stars**: 2,998
**Language**: TypeScript (Electron 41 + React 19)
**License**: MIT
**Platform**: macOS (Apple Silicon + Intel), Windows, Linux

### Feature Inventory

- Voice dictation: global hotkey → dictate into any app with automatic pasting
- AI agent: talk to GPT-5, Claude, Gemini, Groq, or local models with a named voice assistant
- Meeting transcription: auto-detect Zoom, Teams, FaceTime calls; live speaker diarization; voice fingerprinting; Google Calendar integration
- Local speaker diarization: on-device speaker labelling with voice fingerprint recognition across meetings (no cloud)
- Notes: create/organise/search with folders, semantic search, cloud sync, AI actions
- Local or cloud transcription: Whisper (whisper.cpp), NVIDIA Parakeet (sherpa-onnx), cloud providers
- Public API and MCP server: programmatic access to notes and transcriptions; Claude/other AI assistants can call the MCP server
- All core features work with local models (no API key needed)

### Architecture Notes

- Electron 41 + React 19 + Tailwind CSS v4 + better-sqlite3 + shadcn/ui
- whisper.cpp for local Whisper; sherpa-onnx for Parakeet
- Semantic search over notes (embedding model via HF)
- MCP server exposes transcription history and notes to AI assistants

### Agrapha Relevance

- **Auto-detect meeting apps (Zoom/Teams/FaceTime)**: Agrapha could auto-start recording when a known video-call app becomes active — reduces friction dramatically for meeting users
- **Voice fingerprinting across sessions**: persistent speaker identity across meetings ("Alice from Acme always sounds like this") rather than per-meeting anonymous Speaker 1/2. Directly relevant to Agrapha's diarization
- **MCP server**: exposing Agrapha's transcript history via MCP would let Claude Desktop, Cursor, or any MCP-aware AI assistant query past meeting content. Low implementation cost against Agrapha's existing API surface
- **Semantic search over transcripts**: SQLDelight + a local embedding model (whisper-derived or sentence-transformers) for "find the meeting where we discussed pricing" — high value for Agrapha's memory-system export narrative

### Attribution Note

> Auto-detection of video-call applications, voice fingerprinting design, and MCP server integration inspired by [OpenWhispr](https://github.com/OpenWhispr/openwhispr) (MIT).

---

## Project 3 — WhisperWriter

**URL**: https://github.com/savbell/whisper-writer
**Stars**: 1,049
**Language**: Python (PyQt5 GUI, faster-whisper)
**License**: MIT (implied — no LICENSE file found but standard open-source practices stated)
**Platform**: Windows, macOS, Linux

### Feature Inventory

- Four recording modes: `continuous` (auto-restart until shortcut pressed again), `voice_activity_detection` (stop on silence, wait for re-trigger), `press_to_toggle`, `hold_to_record`
- VAD filter via Silero (optional); silence duration configurable (default 900 ms)
- Local model (faster-whisper / CTranslate2) or OpenAI API (or any OpenAI-compatible local endpoint like LocalAI)
- Configurable `initial_prompt` for domain vocabulary conditioning
- `condition_on_previous_text`: uses previous transcription as next prompt (improves coherence in continuous mode)
- Post-processing: remove trailing period, add trailing space, remove capitalisation, configurable key-press delay
- Status window (small, hideable) shows current stage (recording / transcribing)
- Configurable `activation_key` (default `ctrl+shift+space`)
- `input_method`: pynput (default) or alternative backends

### Architecture Notes

- Python + PyQt5; faster-whisper (CTranslate2) for local inference — not portable to JVM
- Design patterns (four recording modes, condition_on_previous_text, initial_prompt for vocab) are directly portable

### Agrapha Relevance

- **`condition_on_previous_text`**: pass the previous chunk's transcription as Whisper's initial_prompt for long continuous recordings. Reduces repetition errors at chunk boundaries. Agrapha's meeting chunking could adopt this immediately via the existing JNI bridge (WhisperParams already accepts initial_prompt)
- **Four recording modes in one settings field**: clean enum design; Agrapha should offer at minimum `continuous` (meeting mode) and `hold_to_record` (dictation mode)
- **Local API endpoint support**: swap OpenAI API for a local whisper.cpp server; Agrapha could add a remote Whisper endpoint fallback for users with a faster Mac on their LAN

### Attribution Note

> Continuous recording mode with previous-text conditioning and four recording mode design inspired by [WhisperWriter](https://github.com/savbell/whisper-writer) (MIT).

---

## Project 4 — open-wispr

**URL**: https://github.com/human37/open-wispr
**Stars**: 127
**Language**: Swift (native macOS app)
**License**: MIT
**Platform**: macOS only (Apple Silicon, Metal acceleration)

### Feature Inventory

- Global hotkey to start/stop recording
- Whisper.cpp on CPU/GPU (Metal); temp file approach (audio → temp file → transcribe → delete)
- Fully offline; no network requests except model download
- Pastes transcription to active app
- Open-source Swift app; simple, minimal codebase

### Architecture Notes

- Native Swift + whisper.cpp; Metal GPU via Core ML/whisper.cpp metal backend
- Very similar architecture to what Agrapha does, but in Swift instead of Kotlin
- Small codebase (~few hundred lines) — useful as a reference implementation for macOS-specific integration patterns (accessibility API for paste, hotkey registration via NSEvent)

### Agrapha Relevance

- **macOS accessibility API for paste to active app**: open-wispr's Swift source shows how to use `AXUIElement` and `CGEvent` to inject text into the frontmost app without Ctrl+V. Agrapha's JNI could call the same macOS APIs via JNA or a lightweight Swift bridge
- **Global hotkey registration**: shows the correct `addGlobalMonitorForEvents` / `addLocalMonitorForEvents` approach on macOS for hotkeys without accessibility permissions

### Attribution Note

> macOS-native global hotkey registration and accessibility API paste design inspired by [open-wispr](https://github.com/human37/open-wispr) (MIT).

---

## Project 5 — whisper-mac (Explosion-Scratch)

**URL**: https://github.com/Explosion-Scratch/whisper-mac
**Stars**: 45
**Language**: TypeScript (Electron/Tauri)
**License**: Not specified
**Platform**: macOS

### Feature Inventory

- Local-first transcription for macOS
- Supports Parakeet, WhisperCPP, Vosk, macOS native Speech framework (all local), or cloud (Gemini, Mistral)
- Described as "extensible" — plugin-friendly architecture for adding engines

### Architecture Notes

- Low star count and no license specified; treat as inspiration only, not for attribution
- Most interesting differentiator: **macOS native Speech framework** (SFSpeechRecognizer) as one of the backends — zero additional model download, built into every Mac since macOS 10.15

### Agrapha Relevance

- **macOS native Speech framework as a fast/free engine**: SFSpeechRecognizer runs on-device (no download), supports English well, and is already optimised by Apple. Could be offered as the "quick start" engine before a user has downloaded a Whisper model. Latency is ~100–200 ms for short utterances
- Note: SFSpeechRecognizer sends audio to Apple servers by default unless `requiresOnDeviceRecognition = true` is set (available iOS 13+ / macOS 12+). This restriction must be surfaced to users in Agrapha's privacy model

### Attribution Note

> macOS native Speech framework engine integration pattern noted from [whisper-mac](https://github.com/Explosion-Scratch/whisper-mac).

---

## Summary Table

| Project | Stars | Language | Most Relevant Feature for Agrapha |
|---------|-------|----------|------------------------------------|
| [Meetily](https://github.com/Zackriya-Solutions/meetily) | 11,649 | Rust | SortFormer real-time diarization; audio ducking |
| [OpenWhispr](https://github.com/OpenWhispr/openwhispr) | 2,998 | TypeScript/Electron | Meeting app auto-detection; voice fingerprinting; MCP server |
| [WhisperWriter](https://github.com/savbell/whisper-writer) | 1,049 | Python | condition_on_previous_text; four recording modes |
| [open-wispr](https://github.com/human37/open-wispr) | 127 | Swift | macOS accessibility API paste; hotkey registration |
| [whisper-mac](https://github.com/Explosion-Scratch/whisper-mac) | 45 | TypeScript | macOS native Speech framework as engine |

---

## Additional Discoveries

Three additional projects discovered in supplementary search, none of which overlapped with the five projects above.

---

### Discovery 1 — argmax-oss-swift (WhisperKit + SpeakerKit + TTSKit)

**URL**: https://github.com/argmaxinc/argmax-oss-swift
**Stars**: 6,072
**Language**: Swift
**License**: MIT
**Platform**: macOS, iOS, visionOS (Apple Silicon)

#### Feature Inventory

- **WhisperKit**: CoreML-accelerated Whisper STT, streaming chunk-by-chunk transcription, word-level timestamps, SRT/VTT subtitle export, custom vocabulary, multi-language
- **SpeakerKit**: on-device speaker diarization via pyannote ONNX; combines with WhisperKit via a single API call to produce diarized transcripts
- **TTSKit**: on-device text-to-speech via Qwen-TTS; real-time streaming playback; custom voice styles; saves audio to file
- Local HTTP server exposing OpenAI-compatible `/v1/audio/transcriptions` endpoint — drop-in for apps that already talk to OpenAI Whisper API
- Swift Package Manager distribution; CLI (`whisperkit-cli`, `speakerkit-cli`, `ttskit-cli`) for scripting
- SRT/VTT subtitle file export built into WhisperKit

#### Architecture Notes

- Pure Swift; CoreML for inference — no Python dependency, no whisper.cpp JNI bridge required
- SpeakerKit wraps pyannote ONNX with a Swift-native API; RTTM output format for downstream tooling
- Pro SDK (closed source) adds real-time diarization, Android Kotlin support, Argmax Local Server for non-native apps
- Hugging Face model hub for model downloads (100k+ downloads/month)

#### Agrapha Relevance

- **SRT/VTT export**: WhisperKit's built-in subtitle export shows the exact data model needed (word timestamps → segment grouping → SRT formatting). Agrapha can adopt the same approach over its existing JNI bridge without a full Swift rewrite
- **TTSKit as read-back engine**: Agrapha currently has no TTS. TTSKit is a ready-made on-device TTS library for Apple Silicon; could be called from Agrapha via a thin Swift JNI bridge to provide "read meeting back aloud" or dictation confirmation audio
- **SpeakerKit diarization API design**: SpeakerKit's Swift API (`SpeakerKit.diarize(audioURL:)` → `[(speaker, start, end)]`) is a clean interface pattern worth mirroring in Agrapha's `DiarizationService` abstraction layer
- **OpenAI-compatible local server**: Agrapha could expose the same `/v1/audio/transcriptions` endpoint, enabling integration with any tool that already speaks OpenAI Whisper API

#### Attribution Note

> SRT/VTT export structure, TTSKit on-device TTS integration pattern, and SpeakerKit diarization API design noted from [argmax-oss-swift](https://github.com/argmaxinc/argmax-oss-swift) (MIT).

---

### Discovery 2 — noScribe

**URL**: https://github.com/kaixxx/noScribe
**Stars**: 1,964
**Language**: Python (faster-whisper + pyannote + custom Qt editor)
**License**: GPL-3.0
**Platform**: macOS (Apple Silicon + Intel), Windows, Linux

#### Feature Inventory

- Automated transcription of recorded interviews and spoken content using faster-whisper (CTranslate2)
- Speaker diarization via pyannote.audio; distinguishes multiple speakers in post-processed audio
- Built-in transcript editor (noScribeEdit) for reviewing, correcting, and annotating transcripts
- GPU acceleration: CUDA (NVIDIA) on Windows/Linux; Apple Silicon Metal path on macOS
- Supports ~60 languages
- Targeted at qualitative social research and journalism (structured for verbatim interview transcription)
- Completely offline; no network calls after model download
- Free, always-free policy; actively maintained

#### Architecture Notes

- Python application; not portable as a library to JVM — design patterns only
- Distributes as a bundled installer (not available via Homebrew or package manager)
- Editor is a separate app (noScribeEdit) shipped alongside the transcriber
- GPL-3.0 license: cannot copy code into Agrapha (Apache/MIT), but design patterns are freely borrowable

#### Agrapha Relevance

- **Dedicated transcript editor UX**: noScribe ships a standalone correction editor tightly integrated with the transcription output. Agrapha's roadmap could include an inline transcript editor (click to correct a word, re-run a segment) — noScribe's UX pattern is the reference implementation
- **Interview/research mode vs. meeting mode**: noScribe is optimised for long, high-quality post-processed transcription (one hour in, three hours out) rather than real-time. Agrapha could add an "accuracy mode" that uses a larger model and longer processing time for important archived recordings
- **pyannote diarization pipeline details**: noScribe's open source shows how pyannote is tuned for interview-style audio (2–4 speakers, conversational overlap) — relevant parameters for Agrapha's DiarizationService configuration

#### Attribution Note

> Transcript correction editor UX and accuracy-mode (post-processed, large-model) transcription design noted from [noScribe](https://github.com/kaixxx/noScribe) (GPL-3.0 — patterns only, no code reuse).

---

### Discovery 3 — Hex

**URL**: https://github.com/kitlangton/Hex
**Stars**: 2,030
**Language**: Swift (SwiftUI + Swift Composable Architecture)
**License**: MIT
**Platform**: macOS, Apple Silicon only

#### Feature Inventory

- Global hotkey dictation: press-and-hold or double-tap-to-lock recording modes
- Dual engine support: **Parakeet TDT v3** (via FluidAudio, default — fast, multilingual, cloud-optimised) and **WhisperKit** (fully on-device)
- Transcribed text pasted into frontmost app via macOS accessibility API
- Changeset-based release workflow; actively developed
- Homebrew Cask distribution (`brew install --cask kitlangton-hex`)
- MIT licensed

#### Architecture Notes

- Swift + SwiftUI + Swift Composable Architecture (TCA) for state management
- FluidAudio library wraps Parakeet TDT v3 (NeMo model from NVIDIA); WhisperKit as fallback/alternative
- Engine selection is a user preference — demonstrates clean multi-engine abstraction in Swift
- Very small focused codebase; press-and-hold vs. lock-toggle is the entire UX

#### Agrapha Relevance

- **Multi-engine abstraction (Parakeet + Whisper)**: Hex shows how to cleanly expose two radically different STT backends (cloud-optimised Parakeet vs. fully local WhisperKit) under a single preference toggle. Agrapha's engine abstraction layer (`WhisperTranscriptionService`) could use the same pattern to add Parakeet or macOS SFSpeechRecognizer as alternative engines
- **Parakeet TDT v3 via FluidAudio**: Parakeet is reported to be significantly faster than Whisper for real-time dictation. Agrapha could evaluate FluidAudio as a low-latency dictation engine alternative to whisper.cpp, especially for the planned "instant dictation" mode
- **Press-and-hold vs. lock-toggle UX**: Two recording modes in a single global hotkey interaction (hold = momentary, double-tap = latching) is a UX pattern worth adopting in Agrapha to reduce the need for a visible UI during dictation

#### Attribution Note

> Multi-engine toggle design (Parakeet + WhisperKit) and press-and-hold vs. lock-toggle recording hotkey UX inspired by [Hex](https://github.com/kitlangton/Hex) (MIT).

---

### Additional Discoveries Summary Table

| Project | Stars | Language | Most Relevant Feature for Agrapha |
|---------|-------|----------|------------------------------------|
| [argmax-oss-swift (WhisperKit)](https://github.com/argmaxinc/argmax-oss-swift) | 6,072 | Swift | SRT/VTT export; TTSKit on-device TTS; SpeakerKit diarization API |
| [noScribe](https://github.com/kaixxx/noScribe) | 1,964 | Python | Transcript correction editor UX; accuracy-mode (large model) transcription |
| [Hex](https://github.com/kitlangton/Hex) | 2,030 | Swift | Multi-engine abstraction (Parakeet + WhisperKit); press-hold vs. lock-toggle hotkey |
