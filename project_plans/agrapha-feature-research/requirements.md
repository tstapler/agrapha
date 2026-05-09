# Agrapha Feature Research — Requirements

## Overview

Survey open-source local-first speech-to-text and meeting-transcription projects to build a prioritized feature backlog for Agrapha. Each backlog item must include an attribution note (project name + URL) for the README.

## Current State of Agrapha

- macOS desktop app (Kotlin Multiplatform + Compose Desktop)
- Records both mic and system audio via CoreAudio/ScreenCaptureKit JNI
- Transcribes locally with Whisper.cpp via JNI (Apple Neural Engine / CoreML)
- Optional speaker diarization via pyannote.audio
- Optional transcript correction + summarization via LLM (Ollama, OpenAI, Anthropic)
- Exports to Logseq (journal entries with [[links]])
- Outputs: key points, decisions, action items, full transcript
- Stack: Kotlin Multiplatform · Compose Desktop · SQLDelight · Whisper.cpp JNI

## Seed References (for README attribution)

| Project | URL | Stars | Language | Focus |
|---------|-----|-------|----------|-------|
| voxtype | https://github.com/peteonrails/voxtype | 712 | Rust | Push-to-talk STT for Linux/Wayland; 7 engines; meeting mode |
| BlahST | https://github.com/QuantiusBenignus/BlahST | 172 | Shell | Lean whisper.cpp wrapper; LLM integration; continuous dictation |
| Handy | https://github.com/cjpais/Handy | 21364 | Rust/Tauri | Cross-platform desktop STT; VAD; Parakeet; extensible |

Research should also discover 3–5 additional comparable projects.

## Feature Areas of Interest

1. **Push-to-talk / dictation mode** — hold-key-to-record anywhere on screen (not just during meetings)
2. **Additional transcription engines** — Parakeet, Moonshine, SenseVoice, Paraformer beyond Whisper
3. **LLM integration patterns** — speech-to-LLM chat, AI proofreader, one-shot assistant, TTS responses
4. **Export formats** — Markdown, JSON, SRT, VTT (beyond current Logseq/plain-markdown)

## Deliverable

A **prioritized feature backlog** structured as:

```
## Feature: <name>
**Priority:** High / Medium / Low
**Inspired by:** <Project>(s) with URL(s)
**What they do:** <1–2 sentences>
**What Agrapha would do:** <1–2 sentences scoped to Agrapha's macOS/meeting context>
**Attribution note (README):** <exact text to add>
**Effort estimate:** XS / S / M / L / XL
```

Items should be ordered: High priority first, then Medium, then Low.

## Constraints

- Agrapha is macOS-only (Intel + Apple Silicon); Linux-specific features are inspiration only
- Core value proposition is meeting transcription + memory system export — features must fit that context or expand it coherently
- Attribution accuracy matters: only credit a project for things they actually do
- Research agent must also search for comparable projects not in the seed list
