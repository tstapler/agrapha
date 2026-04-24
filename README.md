# Agrapha

[![Build](https://img.shields.io/github/actions/workflow/status/tstapler/agrapha/build.yml?branch=main)](https://github.com/tstapler/agrapha/actions)
[![License: ELv2](https://img.shields.io/badge/License-ELv2-blue.svg)](LICENSE)

Source-available, fully local meeting transcription that fits your memory system.

> *Agrapha are the sayings of Socrates and Jesus that were never written down. This app exists so yours aren't.*

<!-- screenshot -->

Records your calls. Transcribes on-device with Whisper. Delivers structured output — key points, decisions, action items, full transcript — to your memory system. Your audio never leaves your machine.

---

## Install

### DMG

Download `Agrapha.dmg` from [releases](https://github.com/tstapler/agrapha/releases). Mount, drag to Applications.

> macOS will warn about an unidentified developer on first launch. Right-click → Open to proceed, or run:
> ```bash
> xattr -dr com.apple.quarantine /Applications/Agrapha.app
> ```

### Homebrew

```bash
brew tap tstapler/agrapha
brew install --cask agrapha
```

### Build from source

Requires macOS 12+, JDK 17+.

```bash
git clone https://github.com/tstapler/agrapha
cd agrapha
./gradlew :composeApp:packageReleaseDmg
# Output: composeApp/build/compose/binaries/main-release/dmg/Agrapha-1.0.0.dmg
```

To run without packaging:

```bash
./gradlew :composeApp:run
```

---

## First run

1. Open Agrapha → Settings → download a Whisper model. `distil-large-v3` recommended (1.45 GB).
2. Grant microphone permission when prompted.
3. Grant screen recording permission: System Settings → Privacy → Screen Recording. Required for system audio capture.
4. Start a call → click Record → structured summary appears when the call ends.

---

## How it works

1. Captures both audio channels: microphone via CoreAudio JNI, system audio via ScreenCaptureKit JNI.
2. Transcribes locally with Whisper.cpp via JNI. Runs on Apple Neural Engine via CoreML.
3. Optional: speaker diarization via pyannote.audio. Requires a Hugging Face token for initial model download.
4. Optional: transcript correction via a configured LLM (Ollama, OpenAI, or Anthropic).
5. Summarizes with the configured LLM. Produces key points, decisions, and action items.
6. Exports to Logseq or any configured target.

**Stack:** Kotlin Multiplatform · Compose Desktop · SQLDelight · Whisper.cpp JNI · ScreenCaptureKit · pyannote.audio

Supports Apple Silicon and Intel. Fully auditable — every line is in this repo.

---

## Integrations

**Logseq** — Set your wiki path in Settings. Meeting notes export as journal entries with `[[links]]`.

**Obsidian, plain markdown, custom scripts** — Planned. Contributions welcome.

---

## vs. the alternatives

| | Agrapha | talat | Granola | MacWhisper |
|---|---|---|---|---|
| Price | Free | $49 | Subscription | Paid |
| Local-first | Yes | Yes | No (cloud) | Yes |
| Source-available | Yes (ELv2) | No | No | No |
| Live capture | Yes | Yes | Yes | No (file import) |
| System audio | Yes | Yes | Yes | No |
| Memory system export | Yes | No | No | No |

It's talat but source-available. Everything runs on your machine, the output goes wherever you want it, and you can read every line of the code.

---

## Contributing

Most wanted:

- Integration targets (Obsidian, Notion, plain markdown)
- Non-macOS audio backends (Linux PipeWire, Windows WASAPI)
- LLM provider integrations

---

## License

Elastic License 2.0 — see [LICENSE](LICENSE). Free to use, modify, and distribute for non-SaaS purposes.
