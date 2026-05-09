# Agrapha Feature Backlog

Prioritized feature backlog derived from research into VoxType, BlahST, Handy, Meetily, OpenWhispr, WhisperWriter, open-wispr, and whisper-mac. Items are ordered High → Medium → Low within each section.

---

## HIGH PRIORITY

---

## Feature: Custom Vocabulary / Dictionary Injection
**Priority:** High
**Inspired by:** [Handy](https://github.com/cjpais/Handy), [WhisperWriter](https://github.com/savbell/whisper-writer)
**What they do:** Handy accepts a `custom_words` list that is injected as Whisper's `initial_prompt` parameter and as a Parakeet custom vocabulary, with fuzzy-match post-correction. WhisperWriter exposes `initial_prompt` directly as a config field for domain conditioning.
**What Agrapha would do:** Allow users to define a persistent list of names, project codes, and technical terms; inject them as Whisper's `initial_prompt` via the existing JNI bridge so beam search favors those tokens, with optional fuzzy-match correction post-transcription.
**Attribution note (README):** Custom vocabulary / dictionary injection pattern inspired by [Handy](https://github.com/cjpais/Handy) (MIT) and [WhisperWriter](https://github.com/savbell/whisper-writer) (MIT).
**Effort estimate:** S
**Notes:** `WhisperParams` in Agrapha's JNI bridge already has an `initial_prompt` field — this is mostly UI + persistence (SQLDelight) work. Fuzzy-match post-correction is optional and can ship in a follow-up.

---

## Feature: Filler Word Stripping
**Priority:** High
**Inspired by:** [Handy](https://github.com/cjpais/Handy)
**What they do:** Handy strips a configurable list of filler words (e.g., "um", "uh", "you know") from transcription output as a post-processing step.
**What Agrapha would do:** Apply a configurable regex/list replacement pass after transcription to remove common filler words from meeting transcripts, producing cleaner minutes and summaries.
**Attribution note (README):** Filler word stripping pattern inspired by [Handy](https://github.com/cjpais/Handy) (MIT).
**Effort estimate:** XS
**Notes:** Pure Kotlin string post-processing; zero JNI changes needed. Default list should include "um", "uh", "like", "you know", "sort of". Let users extend the list.

---

## Feature: Audio Feedback on Recording Start / Stop
**Priority:** High
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype), [Handy](https://github.com/cjpais/Handy)
**What they do:** VoxType plays themed WAV sounds on recording start, stop, and error. Handy exposes `audio_feedback: bool` and `audio_feedback_volume: f32` with a `SoundTheme` enum.
**What Agrapha would do:** Play a short system sound or bundled audio clip when meeting recording starts and stops so users get eyes-free confirmation of state changes.
**Attribution note (README):** Audio feedback on recording start/stop inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT) and [Handy](https://github.com/cjpais/Handy) (MIT).
**Effort estimate:** XS
**Notes:** macOS provides `NSSound` and `AudioServicesPlaySystemSound`; accessible from Kotlin via JNA or a 10-line Swift/ObjC helper. Bundle 2–3 sounds (start, stop, error). Volume knob in settings.

---

## Feature: Toggle vs Push-to-Talk Recording Modes
**Priority:** High
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype), [Handy](https://github.com/cjpais/Handy), [WhisperWriter](https://github.com/savbell/whisper-writer)
**What they do:** All three expose a boolean toggle between push-to-talk (hold key → recording, release → transcribe) and toggle modes (press once to start, press again to stop). WhisperWriter additionally offers a `continuous` mode (auto-restart after each segment) and a `voice_activity_detection` mode.
**What Agrapha would do:** Add a `RecordingMode` enum (`MEETING_CONTINUOUS`, `TOGGLE`, `PUSH_TO_TALK`) to the settings UI. Meeting mode remains the default; toggle and push-to-talk are available for dictation use cases. VAD-based auto-stop can be a follow-up.
**Attribution note (README):** Toggle and push-to-talk recording mode design inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT), [Handy](https://github.com/cjpais/Handy) (MIT), and [WhisperWriter](https://github.com/savbell/whisper-writer) (MIT).
**Effort estimate:** S
**Notes:** Requires a global hotkey listener on macOS (see Global Hotkey / Dictation Mode feature). The mode enum should be persisted in the existing settings store.

---

## Feature: Global Hotkey / Dictation Mode (Paste to Active App)
**Priority:** High
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype), [Handy](https://github.com/cjpais/Handy), [open-wispr](https://github.com/human37/open-wispr), [BlahST](https://github.com/QuantiusBenignus/BlahST)
**What they do:** All four projects allow a global keyboard shortcut to trigger recording from any app, then paste the transcription result at the keyboard caret — Handy via `rdev` keyboard injection or clipboard+Ctrl+V, open-wispr via `AXUIElement`/`CGEvent`, BlahST via xdotool/ydotool.
**What Agrapha would do:** Register a configurable global hotkey via macOS `CGEventTap` or `addGlobalMonitorForEvents` (JNA or Swift bridge), record until key release or toggle-off, transcribe, then inject text into the frontmost app via the macOS Accessibility API (`AXUIElement`) with a Ctrl+V clipboard fallback.
**Attribution note (README):** Global hotkey dictation mode and macOS accessibility-API paste design inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT), [Handy](https://github.com/cjpais/Handy) (MIT), [open-wispr](https://github.com/human37/open-wispr) (MIT), and [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT).
**Effort estimate:** M
**Notes:** Requires `com.apple.security.automation.apple-events` and Accessibility permission in macOS entitlements. JNA can call the C-level `CGEventTap` APIs directly without a Swift bridge. This is a foundational dependency for push-to-talk and toggle modes.

---

## Feature: Previous-Chunk Text Conditioning
**Priority:** High
**Inspired by:** [WhisperWriter](https://github.com/savbell/whisper-writer)
**What they do:** WhisperWriter passes the previous transcription chunk's text as Whisper's `initial_prompt` for the next chunk, reducing repetition artifacts and improving coherence across segment boundaries in continuous recordings.
**What Agrapha would do:** In meeting (continuous) mode, automatically carry forward the last N words of the previous transcription segment as the Whisper `initial_prompt` for the next segment, improving transcript coherence without any user action.
**Attribution note (README):** Previous-chunk text conditioning for continuous transcription inspired by [WhisperWriter](https://github.com/savbell/whisper-writer) (MIT).
**Effort estimate:** XS
**Notes:** One-line change in the transcription loop to set `initial_prompt = last_segment_tail`. Synergizes with the custom vocabulary feature (both write to `initial_prompt`; concatenate both). Cap at ~224 tokens to stay within Whisper's context window.

---

## Feature: SRT and VTT Export
**Priority:** High
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype), [WhisperKit](https://github.com/argmaxinc/argmax-oss-swift)
**What they do:** VoxType's meeting mode exports transcriptions to SRT and VTT subtitle formats with per-segment timestamps in addition to Markdown and plain text. WhisperKit has native SRT/VTT export as a first-class feature, documenting the word-timestamp → segment-grouping → subtitle-formatting data model explicitly.
**What Agrapha would do:** Add SRT and VTT as export options alongside the existing Markdown/Logseq export, using Agrapha's existing segment timestamps to generate standard subtitle files consumable by video editors (DaVinci Resolve, Final Cut Pro, Premiere).
**Attribution note (README):** SRT and VTT export format design inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT); subtitle data model (word timestamps → segment grouping) noted from [WhisperKit](https://github.com/argmaxinc/argmax-oss-swift) (MIT).
**Effort estimate:** S
**Notes:** SRT and VTT are text formats with straightforward timestamp serialization. Agrapha already stores per-segment timestamps in SQLDelight; the serializer is ~100 lines of Kotlin. No JNI changes required.

---

## Feature: JSON Export
**Priority:** High
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype)
**What they do:** VoxType exports meeting transcriptions to structured JSON alongside Markdown and subtitle formats, enabling downstream automation and integration with other tools.
**What Agrapha would do:** Export a structured JSON file containing the full transcript with per-segment timestamps, speaker labels (when diarization is enabled), LLM summaries, action items, and decisions — providing a machine-readable format for downstream integrations.
**Attribution note (README):** JSON export format design inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT).
**Effort estimate:** XS
**Notes:** Kotlinx.serialization already in the project. Schema should mirror the existing data model: `{ meeting_id, started_at, segments: [{ speaker, start_ms, end_ms, text }], summary, action_items, decisions }`. Pair with SRT/VTT export in a single "Export Formats" milestone.

---

## Feature: Whisper Model Auto-Unload on Idle
**Priority:** High
**Inspired by:** [Handy](https://github.com/cjpais/Handy)
**What they do:** Handy auto-unloads the Whisper model after a configurable idle timeout using a watcher thread and RAII `LoadingGuard`, freeing 1.5–3 GB of RAM when recording is not active.
**What Agrapha would do:** Add a configurable idle-unload timeout (default: 5 minutes) to Agrapha's Whisper JNI wrapper; after the timeout expires with no transcription activity, free the native model from memory automatically.
**Attribution note (README):** Model idle-unload pattern inspired by [Handy](https://github.com/cjpais/Handy) (MIT).
**Effort estimate:** S
**Notes:** Requires a Kotlin `CoroutineScope` + `delay`-based watcher (or `ScheduledExecutorService`) in the transcription manager. The JNI `freeContext()` call already exists; this is plumbing around it. Reload on next recording start with a brief UI indicator.

---

## Feature: Multiple Named LLM Post-Processing Prompts
**Priority:** High
**Inspired by:** [Handy](https://github.com/cjpais/Handy), [BlahST](https://github.com/QuantiusBenignus/BlahST)
**What they do:** Handy stores multiple named `LLMPrompt` presets selectable per transcription. BlahST dispatches to different system prompts based on spoken keywords ("Summarise…", "Draft email…", "Proofread…").
**What Agrapha would do:** Allow users to define named post-processing prompts beyond the built-in "summary" — e.g., "Action items only", "Draft follow-up email", "Extract decisions" — selectable from a dropdown before or after transcription ends.
**Attribution note (README):** Multiple named LLM post-processing prompts inspired by [Handy](https://github.com/cjpais/Handy) (MIT) and [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT).
**Effort estimate:** S
**Notes:** Agrapha already has an LLM integration layer; this is UI + prompt storage (SQLDelight). Ship 3 built-in presets (Summary, Action Items, Follow-up Email) and let users add custom prompts. Store `post_process_prompt` alongside `post_processed_text` in the existing meetings table.

---

## MEDIUM PRIORITY

---

## Feature: Meeting App Auto-Detection (Auto-Start Recording)
**Priority:** Medium
**Inspired by:** [OpenWhispr](https://github.com/OpenWhispr/openwhispr)
**What they do:** OpenWhispr detects when Zoom, Teams, or FaceTime becomes the active window and automatically starts recording, eliminating the need to manually trigger a meeting session.
**What Agrapha would do:** Poll the macOS window server (via `CGWindowListCopyWindowInfo` or `NSWorkspace.shared.frontmostApplication`) for known video-call app bundle IDs (Zoom, Teams, Google Meet, FaceTime, Webex) and auto-start a recording session when one becomes active, with a configurable allow-list.
**Attribution note (README):** Auto-detection of active video-call applications to trigger recording inspired by [OpenWhispr](https://github.com/OpenWhispr/openwhispr) (MIT).
**Effort estimate:** M
**Notes:** `NSWorkspace.didActivateApplicationNotification` provides the hook; JNA can receive it. Privacy consideration: show a notification before auto-starting so users know recording has begun. Add an opt-in toggle in settings (off by default).

---

## Feature: Parakeet ONNX Engine
**Priority:** High
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype), [Handy](https://github.com/cjpais/Handy), [Hex](https://github.com/kitlangton/Hex)
**What they do:** VoxType and Handy support NVIDIA Parakeet (FastConformer TDT) via ONNX Runtime as an alternative to Whisper, offering ~5× real-time throughput on CPU with comparable English accuracy and no GPU required. Meetily also supports Parakeet models (implementation details not confirmed in available source). Hex provides a production Swift implementation of dual-engine Parakeet+Whisper switching with a user-facing toggle on macOS (Apple Silicon).
**What Agrapha would do:** Add Parakeet as a selectable transcription engine via ONNX Runtime for Java (onnxruntime-java), allowing users on lower-power Macs or with large meeting backlogs to transcribe faster without waiting for Whisper large-v3.
**Attribution note (README):** Parakeet ONNX engine integration pattern inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT) and [Handy](https://github.com/cjpais/Handy) (MIT); multi-engine toggle design pattern from [Hex](https://github.com/kitlangton/Hex) (MIT).
**Effort estimate:** L
**Notes:** ONNX Runtime has an official Java API (`com.microsoft.onnxruntime:onnxruntime`). Parakeet is English-only; diarization integration needs re-validation. Model download (~500 MB) must be handled gracefully. Abstract a `TranscriptionEngine` interface first so Whisper and Parakeet share a common caller.

---

## Feature: Silero VAD (Voice Activity Detection)
**Priority:** Medium
**Inspired by:** [Handy](https://github.com/cjpais/Handy), [WhisperWriter](https://github.com/savbell/whisper-writer)
**What they do:** Handy uses a `SmoothedVad` wrapper over Silero VAD to trim leading/trailing silence from each audio chunk before sending to Whisper, reducing hallucinations and inference latency. WhisperWriter makes the silence duration configurable.
**What Agrapha would do:** Integrate Silero VAD (ONNX model, ~1 MB) via ONNX Runtime for Java to detect and trim silence from each meeting audio chunk before Whisper inference, reducing hallucination artifacts and improving transcription of meeting segments with long pauses.
**Attribution note (README):** Silero VAD integration for silence trimming inspired by [Handy](https://github.com/cjpais/Handy) (MIT) and [WhisperWriter](https://github.com/savbell/whisper-writer) (MIT).
**Effort estimate:** M
**Notes:** Silero VAD ONNX model is ~1 MB; inference is CPU-only and fast. Requires ONNX Runtime dependency (shared with Parakeet if that ships first). For meeting mode, VAD primarily reduces hallucination on silence; for dictation mode, it enables auto-stop. Both use cases justify the dependency.

---

## Feature: Transcription History with Saved-Favourite Flag
**Priority:** Medium
**Inspired by:** [Handy](https://github.com/cjpais/Handy)
**What they do:** Handy stores every transcription in SQLite with fields for raw text, post-processed text, the prompt used, a `saved` boolean flag for user-marked favourites, a title, and a configurable retention period; a Raycast extension browses this history.
**What Agrapha would do:** Extend the existing SQLDelight meetings schema with a `saved` flag, a `post_processed_text` column, and a `post_process_prompt` column; add a searchable history view in the Compose Desktop UI so users can browse, star, and re-export past meetings.
**Attribution note (README):** Transcription history schema with saved-favourite flag inspired by [Handy](https://github.com/cjpais/Handy) (MIT).
**Effort estimate:** M
**Notes:** Schema migration is straightforward with SQLDelight. The history view should support search by keyword and filter by date range. The `saved` flag prevents a meeting from being purged by the retention policy.

---

## Feature: Re-Transcribe with Different Model
**Priority:** Medium
**Inspired by:** [Meetily](https://github.com/Zackriya-Solutions/meetily)
**What they do:** Meetily (Beta) allows importing an existing audio recording and re-transcribing it with a different Whisper model size or language setting, useful when a larger or newer model becomes available.
**What Agrapha would do:** Expose a "Re-transcribe" action on completed meetings that re-runs Whisper (or another engine) against the stored audio file with a selectable model and language, replacing or appending the new transcript while preserving the original.
**Attribution note (README):** Re-transcription with model selection inspired by [Meetily](https://github.com/Zackriya-Solutions/meetily) (MIT).
**Effort estimate:** M
**Notes:** Requires Agrapha to retain the raw audio file after a meeting (currently unclear if it does). If audio is discarded, this feature requires a storage policy change first. Preserve the original transcript as a separate version; do not overwrite.

---

## Feature: Apple Intelligence On-Device Post-Processing
**Priority:** Medium
**Inspired by:** [Handy](https://github.com/cjpais/Handy)
**What they do:** Handy calls Apple Intelligence via a Swift FFI (`extern "C"` bridge) to apply on-device LLM text processing without any API key, checking availability at runtime (`isAppleIntelligenceAvailable()`), and supports M1+ Macs on macOS Sequoia 15.1+.
**What Agrapha would do:** Add Apple Intelligence as an optional LLM provider for transcript correction and summarization, available at no cost on qualifying Macs (M1+, macOS 15.1+), via a Kotlin → JNI → Swift bridge using the same `process_text_with_system_prompt_apple()` pattern.
**Attribution note (README):** Apple Intelligence on-device LLM post-processing integration pattern inspired by [Handy](https://github.com/cjpais/Handy) (MIT).
**Effort estimate:** L
**Notes:** Requires adding a Swift/ObjC compilation step to the Gradle build (non-trivial for a Kotlin-first project). Runtime availability check is mandatory — must degrade gracefully on older hardware. Highest value for privacy-conscious users who want zero cloud calls. Gate behind a feature flag.

---

## Feature: Remote Whisper Inference Endpoint
**Priority:** Medium
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype), [BlahST](https://github.com/QuantiusBenignus/BlahST)
**What they do:** VoxType supports a remote whisper.cpp HTTP server as a backend (`--engine whisper-remote`). BlahST's `-n` flag routes to a LAN server for offload inference, with sub-150 ms round-trip.
**What Agrapha would do:** Allow users to configure a remote whisper.cpp server URL (e.g., a faster Mac on the same LAN) as an alternative to local inference, reducing battery drain on the meeting device and enabling access to larger Whisper models without local RAM constraints.
**Attribution note (README):** Remote whisper.cpp server endpoint design inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT) and [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT).
**Effort estimate:** S
**Notes:** whisper.cpp ships a simple HTTP server mode. Agrapha's JNI layer would be bypassed; audio chunks POSTed to the server endpoint instead. Use `ktor-client` (already likely in the stack) for the HTTP call. Requires LAN latency consideration for chunked meeting transcription.

---

## Feature: One-Shot Speech-to-LLM (Dictated Question → AI Answer)
**Priority:** Medium
**Inspired by:** [BlahST](https://github.com/QuantiusBenignus/BlahST)
**What they do:** BlahST's `wsiAI` script captures a spoken question, transcribes it, sends it to a local llama-server with a system prompt, receives a text response, speaks it via Piper TTS, and places the answer in the clipboard.
**What Agrapha would do:** Add a "Quick Ask" mode accessible from the menu bar: press a hotkey, dictate a question, and Agrapha transcribes and routes it to the configured LLM (Ollama/OpenAI/Anthropic), displaying the answer in a floating window with a "Copy" button.
**Attribution note (README):** One-shot speech-to-LLM assistant mode inspired by [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT).
**Effort estimate:** M
**Notes:** Depends on the Global Hotkey / Dictation Mode feature. The LLM call reuses Agrapha's existing provider abstractions. The floating window is a new Compose Desktop surface. TTS response (via macOS `say` or a local model) is optional and can ship separately.

---

## Feature: macOS Menu Bar Recording Status Indicator
**Priority:** Medium
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype)
**What they do:** VoxType emits a live JSON status feed (`voxtype status --follow --format json`) consumed by Waybar/polybar to show recording state, active model, and backend in the system bar at all times.
**What Agrapha would do:** Add a persistent macOS menu bar extra (NSStatusItem) showing Agrapha's current state (idle, recording, transcribing) with a simple icon, giving users always-visible recording confirmation without needing to keep the main window open.
**Attribution note (README):** Always-visible recording status indicator concept inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT).
**Effort estimate:** M
**Notes:** Compose Desktop does not natively support `NSStatusItem`; requires a JNA call or a small Swift helper. The AWT SystemTray API is cross-platform but renders poorly on macOS. Recommend a thin Swift/ObjC `NSStatusItem` bridge called from Kotlin via JNI. Useful for dictation mode regardless of meeting context.

---

## Feature: Semantic Search over Transcripts
**Priority:** Medium
**Inspired by:** [OpenWhispr](https://github.com/OpenWhispr/openwhispr)
**What they do:** OpenWhispr embeds transcript text using a local embedding model and supports semantic (vector similarity) search over the notes and transcript history — enabling queries like "find the meeting where we discussed pricing" rather than exact keyword search.
**What Agrapha would do:** Generate embeddings for each meeting transcript (using a local sentence-transformers-compatible model via ONNX) and store them in SQLDelight or a lightweight vector store, enabling semantic search from the history view as a complement to keyword search.
**Attribution note (README):** Semantic search over transcripts inspired by [OpenWhispr](https://github.com/OpenWhispr/openwhispr) (MIT).
**Effort estimate:** L
**Notes:** Embedding model (~80 MB, e.g., all-MiniLM-L6-v2 ONNX) is the main download cost. SQLite supports basic vector operations via the sqlite-vec extension or a file-based FAISS index. High value for the "memory system" narrative but L effort; defer until transcript history is shipped.

---

## Feature: Voice Fingerprinting Across Sessions
**Priority:** Medium
**Inspired by:** [OpenWhispr](https://github.com/OpenWhispr/openwhispr)
**What they do:** OpenWhispr builds persistent voice fingerprints for speakers across multiple meetings, so "Alice from Acme" is automatically identified in future recordings rather than appearing as an anonymous "Speaker 2."
**What Agrapha would do:** Extend Agrapha's existing pyannote.audio diarization to persist speaker embeddings in a SQLDelight speaker table, allowing the user to name speakers once and have them auto-labeled in future meetings with the same participants.
**Attribution note (README):** Persistent voice fingerprinting across sessions inspired by [OpenWhispr](https://github.com/OpenWhispr/openwhispr) (MIT).
**Effort estimate:** L
**Notes:** pyannote.audio already produces speaker embeddings; storing and comparing them is the new work. Requires a cosine similarity threshold tuned to avoid false matches. Privacy-sensitive: speaker profiles should be opt-in and stored locally only.

---

## Feature: macOS Native Speech Framework Engine (SFSpeechRecognizer)
**Priority:** Medium
**Inspired by:** [whisper-mac](https://github.com/Explosion-Scratch/whisper-mac)
**What they do:** whisper-mac lists macOS native `SFSpeechRecognizer` as one of its supported local transcription backends — zero model download, built into every Mac since macOS 10.15, ~100–200 ms latency for short utterances.
**What Agrapha would do:** Offer `SFSpeechRecognizer` as a lightweight "no-download" engine option for users who haven't yet downloaded a Whisper model, providing an immediate out-of-box experience; surface the `requiresOnDeviceRecognition` privacy flag prominently.
**Attribution note (README):** No external attribution — `SFSpeechRecognizer` is an Apple-documented API; whisper-mac (license unspecified) treated as inspiration only, no attribution.
**Effort estimate:** M
**Notes:** `SFSpeechRecognizer` is Swift/ObjC only; requires a JNI bridge. `requiresOnDeviceRecognition = true` must be set for privacy compliance — document clearly that without it audio is sent to Apple servers. Quality is lower than Whisper small for technical vocabulary; position as a "quick start" engine, not a meeting-quality engine.

---

## Feature: Inline Transcript Correction Editor
**Priority:** Medium
**Inspired by:** [noScribe](https://github.com/kaixxx/noScribe)
**What they do:** noScribe ships a dedicated transcript editor (noScribeEdit) for manual correction of whisper output — word-level editing with speaker labels, confidence highlights, and playback sync.
**What Agrapha would do:** An editable transcript view in the meeting summary screen, allowing users to fix misheard words, correct speaker labels, and re-run summarization against the corrected text before exporting.
**Attribution note (README):** Transcript correction editor design inspired by [noScribe](https://github.com/kaixxx/noScribe) (GPL-3.0; patterns only — no code reuse).
**Effort estimate:** L
**Notes:** noScribe is GPL-3.0 — architecture inspiration only, no code can be incorporated. Requires extending SQLDelight schema to store corrected transcript alongside raw whisper output.

---

## LOW PRIORITY

---

## Feature: Spoken Punctuation Post-Processing
**Priority:** Low
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype)
**What they do:** VoxType converts spoken punctuation words ("period" → ".", "open paren" → "(", "new line" → "\n") via a configurable replacement table, designed primarily for developer/technical dictation workflows.
**What Agrapha would do:** Apply an optional post-processing pass that converts a configurable list of spoken words to punctuation or special characters, useful for Agrapha users who dictate code or structured text outside of meeting context.
**Attribution note (README):** Spoken punctuation conversion pattern inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT).
**Effort estimate:** XS
**Notes:** Low value for meeting transcription (the primary use case); most relevant if Agrapha ships a developer-focused dictation mode. Implement as a simple ordered replacement list in settings. Low effort but niche audience.

---

## Feature: CLI Remote Control Flags
**Priority:** Low
**Inspired by:** [Handy](https://github.com/cjpais/Handy)
**What they do:** Handy exposes `--toggle-transcription`, `--toggle-post-process`, and `--cancel` CLI flags that send IPC commands to a running Handy instance, enabling integration with Raycast, Alfred, macOS Shortcuts, and window managers.
**What Agrapha would do:** Expose `agrapha record start|stop|toggle` and `agrapha export` CLI subcommands via a Unix socket or named pipe to the running Agrapha instance, enabling Raycast, Alfred, and macOS Shortcuts integration without opening the main window.
**Attribution note (README):** CLI remote control flag design inspired by [Handy](https://github.com/cjpais/Handy) (MIT).
**Effort estimate:** M
**Notes:** Requires implementing a lightweight IPC server in Agrapha's process (Unix domain socket is cleanest on macOS). High value for power users but low urgency compared to core transcription improvements. Depends on the menu bar indicator being present so users know recording state.

---

## Feature: SortFormer Real-Time Diarization
**Priority:** Low
**Inspired by:** [Meetily](https://github.com/Zackriya-Solutions/meetily)
**What they do:** Meetily uses NVIDIA's SortFormer (ONNX) for real-time on-device speaker diarization, which claims better accuracy than pyannote.audio on live streams and runs fully locally via ONNX Runtime.
**What Agrapha would do:** Evaluate SortFormer as an alternative or supplement to the existing pyannote.audio diarization pipeline, potentially replacing the Python dependency with a pure ONNX Runtime call accessible from the JVM.
**Attribution note (README):** SortFormer ONNX diarization evaluation inspired by [Meetily](https://github.com/Zackriya-Solutions/meetily) (MIT).
**Effort estimate:** XL
**Notes:** Eliminates the pyannote.audio Python dependency (a significant operational improvement) but requires porting the diarization pipeline to ONNX Runtime for Java, which is a large engineering effort. Priority rises significantly if pyannote.audio causes installation pain. Treat as a research spike first.

---

## Feature: MCP Server for Transcript History
**Priority:** Low
**Inspired by:** [OpenWhispr](https://github.com/OpenWhispr/openwhispr)
**What they do:** OpenWhispr exposes a public MCP server that lets Claude Desktop, Cursor, and other MCP-aware AI tools query the user's notes and transcription history programmatically.
**What Agrapha would do:** Expose Agrapha's meeting transcript and summary data via a local MCP server, allowing Claude Desktop or other MCP clients to query "what did we decide in the pricing meeting last week?" against Agrapha's local database.
**Attribution note (README):** MCP server for transcript history access inspired by [OpenWhispr](https://github.com/OpenWhispr/openwhispr) (MIT).
**Effort estimate:** M
**Notes:** MCP protocol is JSON-RPC over stdio or HTTP. The transport layer is simple; the value is in defining a useful schema for meeting data (meetings, segments, speakers, summaries, action items). Highly differentiating for the "memory system" narrative but requires users to already have an MCP-aware client.

---

## Feature: Moonshine Engine (Edge-Optimised ONNX)
**Priority:** Low
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype), [Handy](https://github.com/cjpais/Handy)
**What they do:** VoxType and Handy both support Moonshine (encoder-decoder ONNX, English, edge-optimised) as a transcription engine, offering lower memory usage than Whisper small with comparable English accuracy on short utterances.
**What Agrapha would do:** Add Moonshine as a third selectable engine via ONNX Runtime for Java, offering a smaller download footprint (~100 MB vs Whisper large's 3 GB) for users who only need English transcription.
**Attribution note (README):** Moonshine ONNX engine integration pattern inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT) and [Handy](https://github.com/cjpais/Handy) (MIT).
**Effort estimate:** M
**Notes:** Moonshine is English-only and optimised for short utterances — less suited to long meeting recordings than Whisper or Parakeet. Best delivered after the Parakeet engine is shipped and the `TranscriptionEngine` abstraction is in place. Defers naturally to that milestone.

---

## Feature: Local TTS for Spoken AI Responses
**Priority:** Low
**Inspired by:** [BlahST](https://github.com/QuantiusBenignus/BlahST)
**What they do:** BlahST uses Piper (local neural TTS) to speak LLM responses aloud during `blahstbot` and `blahstream` chat sessions, providing a fully local voice-to-voice conversation loop.
**What Agrapha would do:** Use macOS's built-in `say` command (or a local neural TTS model) to read aloud AI-generated summaries or action items at meeting end, providing an audio digest option for users who prefer not to read the output.
**Attribution note (README):** Local text-to-speech for spoken AI responses inspired by [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT).
**Effort estimate:** S
**Notes:** `say` is zero-dependency on macOS and accessible from Kotlin via `Runtime.exec()`. Neural TTS quality requires Piper or a similar model (~50–200 MB). Meeting-end TTS digest is a niche preference; most users prefer to read summaries. Low priority unless user research shows demand.

---

## Feature: Continuous Dictation Loop (Auto-Restart on Silence)
**Priority:** Low
**Inspired by:** [BlahST](https://github.com/QuantiusBenignus/BlahST)
**What they do:** BlahST's `blooper` runs a continuous loop: record until silence (~3 s), transcribe, paste at caret, immediately restart recording until a longer silence or hotkey interrupt stops the loop.
**What Agrapha would do:** In dictation mode, add a "continuous" sub-mode that automatically restarts recording after each segment is pasted, allowing hands-free long-form dictation into any app without repeated hotkey presses.
**Attribution note (README):** Continuous dictation loop design inspired by [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT).
**Effort estimate:** S
**Notes:** Requires VAD (Silero) to detect silence reliably. Depends on: Global Hotkey / Dictation Mode, Silero VAD, and the toggle recording mode being in place. Low priority because VAD-triggered auto-stop in toggle mode achieves a similar UX with less complexity.

---

## Feature: SenseVoice / Paraformer Multilingual Engines
**Priority:** Low
**Inspired by:** [VoxType](https://github.com/peteonrails/voxtype)
**What they do:** VoxType supports SenseVoice (CTC ONNX, Chinese/English/Japanese/Korean/Cantonese) and Paraformer (non-autoregressive ONNX, Chinese+English bilingual) as alternative engines for non-English-primary users.
**What Agrapha would do:** Add SenseVoice and/or Paraformer as selectable engines via ONNX Runtime for Java for users whose meetings are primarily in Chinese, Japanese, or Korean, complementing Whisper's multilingual support with faster inference for those languages.
**Attribution note (README):** SenseVoice and Paraformer multilingual ONNX engine patterns inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT).
**Effort estimate:** L
**Notes:** Lower priority than Parakeet (English-first user base). Deliver after the `TranscriptionEngine` abstraction and Parakeet are in place. SenseVoice's emotion and event detection (laughter, applause) could be a differentiating feature for meeting transcription — worth noting as a future angle.

---

*Backlog last updated: 2026-05-09. 31 items total: 11 High, 12 Medium, 8 Low.*
