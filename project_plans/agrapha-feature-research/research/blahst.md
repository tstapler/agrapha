# BlahST — Research

## Summary

BlahST (172 stars, zsh shell scripts, MIT) is a minimal Linux STT toolkit built on top of whisper.cpp. It provides six composable scripts — each a hotkey-triggered shell one-liner — covering basic transcription, multilingual input, continuous dictation, LLM chat, and streaming speech-to-speech conversation. Its design philosophy is zero-UI ("the best UI is no UI at all"), using the clipboard or PRIMARY selection as the universal paste mechanism. The LLM integration pattern (speech → transcription → llama-server → Piper TTS) is directly relevant to Agrapha's planned AI features.

## Feature Inventory

### Scripts

| Script | Function |
|--------|----------|
| `wsi` | Core STT: record mic via sox, transcribe via local whisper.cpp or whisper.cpp server API or whisperfile, output to clipboard or PRIMARY selection. Silence-detection stops recording automatically (~2 s of silence at 6% threshold). Prevents duplicate invocations via `pidof` guard. |
| `wsiml` | Multilingual variant of `wsi`: `-l fr` forces language, `-t` translates to English. Supports multiple hotkeys per language. |
| `wsiAI` | One-shot LLM assistant: transcribes speech, constructs prompt ("Assistant …", "Translator …", "Computer, proofread …"), sends to llama-server or llamafile, gets text response, speaks via Piper TTS, and places answer in clipboard. |
| `blooper` | Continuous dictation loop: transcribes in a loop, autopastes on each pause (~3 s silence threshold), exits on longer silence or hotkey interrupt; uses xdotool/ydotool for autopaste. |
| `blahstbot` | Low-latency speech chat: record → whisper.cpp → llama-server → Piper TTS → spoken + clipboard response. Context persists between turns; "RESET CONTEXT" spoken command clears it. `-n` flag uses LAN server for offload. |
| `blahstream` | Streaming speech-to-speech chat: like `blahstbot` but LLM response streamed token-by-token; each chunk spoken and autopasted in real time. Context compression via summarization. X11: buffers paste until original window regains focus. WIP/experimental. |

### Additional Features

- **whisperfile support**: portable single-file whisper model executables (`-w` flag); no compilation needed
- **AI proofreader**: triggered by keyword in speech ("Computer, proofread …"); sends currently selected text to LLM, replaces selection with corrected version — no clipboard interaction required
- **AI translator**: "Translator …" spoken keyword → LLM translates text, speaks result, places in clipboard
- **Clipboard vs. PRIMARY selection**: `wsi` (no flag) uses clipboard (Ctrl+V paste); `wsi -p` uses PRIMARY selection (middle-mouse paste); both support X11 and Wayland
- **Network transcription**: whisper.cpp HTTP server API (`-n` or explicit `IP:PORT` argument); sub-150 ms round-trip on LAN
- **Autopaste**: xdotool (X11) or ydotool (Wayland) simulates Ctrl+V or middle-click after transcription
- **Single-instance guard**: `pidof -q <scriptname> && exit 0` prevents duplicate hotkey presses from stacking
- **Hotkey interrupt**: `pkill rec` as a second hotkey cancels in-flight recording immediately
- **Piper TTS**: neural local TTS for spoken LLM responses; no cloud, supports multiple voice models
- **Centralised config**: all tools share `blahst.cfg`; local per-script overrides possible
- **Microphone indicator**: GNOME top-bar mic icon appears for duration of recording (uses system desktop notification mechanism)

## LLM Integration Approach

BlahST treats each LLM interaction as a one-shot or stateful HTTP call to a locally-running `llama-server` (llama.cpp) or a `llamafile` executable:

1. **Speech capture**: `rec` (sox) captures at 16 kHz until silence
2. **Transcription**: `whisper-cli` (local) or HTTP POST to `/inference` (server/whisperfile)
3. **Prompt construction**: shell string interpolation; keyword in speech ("Computer …", "Assistant …") determines which system prompt to prepend
4. **LLM call**: `curl` POST to `llama-server` API; streaming (`blahstream`) or one-shot (`blahstbot`)
5. **TTS output**: response piped to `piper` for local neural TTS; output played via `aplay`
6. **Clipboard/paste**: response also written to clipboard for manual paste

Context management in `blahstbot` is manual: conversation history held in a shell array, serialised to JSON, truncated or summarised when it grows too large.

## Continuous Dictation Design

`blooper` uses sox silence detection (`silence 1 0.1 1% 1 2.0 5%`) to end each segment, then immediately autopastes and restarts recording. The loop exits on ≥3 s silence or hotkey interrupt (`pkill rec`). Text accumulates at the keyboard caret via xdotool/ydotool. This is entirely within the terminal/shell — no GUI required.

## Architecture Notes

- Pure zsh scripts (~100–300 lines each); no compiled components beyond whisper.cpp and llama.cpp
- All IPC via clipboard, PRIMARY selection, and signals (`pkill`)
- Stateless between invocations (except `blahstbot` conversation array)
- Not portable to macOS as-is (sox flags, xdotool/ydotool, xsel/wl-copy are Linux-specific), but the **design patterns** are portable
- Relevant to Agrapha: the clipboard-as-universal-output pattern, the one-shot LLM prompt construction pattern, and the keyword-triggered AI proofreader concept all map cleanly to Kotlin/Compose

## Agrapha Relevance

| Feature | Rationale |
|---|---|
| **Continuous dictation mode** (`blooper`) | Agrapha is meeting-first but users want always-on dictation between meetings. `blooper`'s loop-until-silence pattern is the reference design: record → transcribe → paste → repeat. Could be Agrapha's "Dictation Mode" toggle. |
| **AI proofreader triggered by selected text** | Select text in any app, say "proofread", and it's replaced. This would complement Agrapha's existing LLM integration at near-zero engineering cost (services layer already exists). |
| **One-shot speech-to-LLM** (`wsiAI` pattern) | User dictates a question; Agrapha sends it to configured LLM; speaks/displays the answer. Natural extension of the existing Ollama/Anthropic/OpenAI integration. |
| **Keyword-dispatch to different LLM prompts** | Spoken prefixes ("Summarise …", "Action items …", "Draft email …") can route to different pre-configured system prompts. Low engineering cost, high UX value in a meeting context. |
| **Network transcription offload** | Agrapha runs on the meeting device; a second Mac or a home server could run whisper-server for faster inference. BlahST's `-n` pattern is the reference for how to add a remote backend. |
| **Piper TTS for spoken responses** | Agrapha could speak AI-generated summaries or action items at meeting end. Piper runs fully locally; the macOS analogue is `say` (built-in) or a native neural TTS. |

## Attribution Note

> Continuous dictation loop design and speech-to-LLM one-shot pattern inspired by [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT).
