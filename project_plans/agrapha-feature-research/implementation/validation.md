# Validation Report — Agrapha Feature Backlog

Validated against: `requirements.md`, `research/voxtype.md`, `research/blahst.md`, `research/handy.md`, `research/comparable-projects.md`
Date: 2026-05-09

---

## Requirements Coverage

| Feature Area | Covered? | Backlog Item(s) |
|---|---|---|
| Push-to-talk / dictation mode | YES | "Toggle vs Push-to-Talk Recording Modes" (High), "Global Hotkey / Dictation Mode" (High) |
| Additional transcription engines beyond Whisper | YES | "Parakeet ONNX Engine" (High), "Moonshine Engine" (Low), "SenseVoice/Paraformer" (Low), "macOS Native Speech Framework" (Medium) |
| LLM integration patterns | YES | "Multiple Named LLM Post-Processing Prompts" (High), "One-Shot Speech-to-LLM" (Medium), "Apple Intelligence On-Device Post-Processing" (Medium) |
| Export formats (Markdown, JSON, SRT, VTT) | YES | "SRT and VTT Export" (High), "JSON Export" (High) |

**Requirements coverage note:** Feature area 2 (additional transcription engines) is now covered by "Parakeet ONNX Engine" (High priority). Parakeet was promoted from Medium to High — it is the only alternative engine with a clear implementation path (ONNX Runtime for Java) and concrete evidence from three projects (VoxType, Handy, Meetily, plus the newly discovered Hex). All four feature areas now have at least one High-priority backlog item.

---

## Attribution Issues

4 issues found.

**Issue 1 — Parakeet ONNX Engine: Meetily's Parakeet implementation method overstated**
- Item: "Parakeet ONNX Engine"
- What's wrong: "What they do" states that VoxType, Handy, and Meetily all support Parakeet "via ONNX Runtime." The research confirms ONNX Runtime for VoxType and Handy. For Meetily, the research only says it supports "Whisper or Parakeet models" — the underlying runtime for Parakeet in Meetily is not confirmed. Meetily's architecture note specifies only "Rust whisper.cpp bindings for transcription; SortFormer ONNX for diarization." Claiming Meetily uses ONNX Runtime specifically for Parakeet is an overstatement.
- Suggested fix: Change "What they do" to: "VoxType and Handy support NVIDIA Parakeet (FastConformer TDT) via ONNX Runtime as an alternative to Whisper. Meetily also supports Parakeet models (implementation details not confirmed in available source)."

**Issue 2 — Global Hotkey / Dictation Mode: BlahST omitted from attribution note**
- Item: "Global Hotkey / Dictation Mode (Paste to Active App)"
- What's wrong: BlahST is listed in the "Inspired by" field and correctly described in "What they do," but it is absent from the "Attribution note (README)" field. The note credits VoxType, Handy, and open-wispr only.
- Suggested fix: Add BlahST to the attribution note: "...inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT), [Handy](https://github.com/cjpais/Handy) (MIT), [open-wispr](https://github.com/human37/open-wispr) (MIT), and [BlahST](https://github.com/QuantiusBenignus/BlahST) (MIT)."

**Issue 3 — Silero VAD: WhisperWriter omitted from attribution note**
- Item: "Silero VAD (Voice Activity Detection)"
- What's wrong: WhisperWriter is correctly listed in "Inspired by" and credited in "What they do" for making silence duration configurable. However, the attribution note credits only Handy.
- Suggested fix: Update attribution note to: "Silero VAD integration for silence trimming inspired by [Handy](https://github.com/cjpais/Handy) (MIT) and [WhisperWriter](https://github.com/savbell/whisper-writer) (MIT)."

**Issue 4 — macOS Native Speech Framework: whisper-mac has no license specified**
- Item: "macOS Native Speech Framework Engine (SFSpeechRecognizer)"
- What's wrong: The attribution note omits a license tag, but more importantly the research explicitly notes whisper-mac has "no license specified" and should be treated as "inspiration only, not for attribution." The backlog promotes it to a full attribution credit anyway.
- Suggested fix: Downgrade the attribution note to a softer acknowledgment — e.g., "macOS native Speech framework engine integration inspired by the engine inventory in [whisper-mac](https://github.com/Explosion-Scratch/whisper-mac) (license unspecified — no code reuse)." Alternatively, cite `SFSpeechRecognizer` as an Apple-documented API and drop the whisper-mac credit entirely, since the underlying technology is Apple's.

---

## Attribution Note Quality

3 items flagged.

**Flag 1 — Global Hotkey / Dictation Mode**: Attribution note is missing BlahST (see Issue 2 above). This makes the note inconsistent with the "Inspired by" field, which is the canonical credit record. Fix as described in Issue 2.

**Flag 2 — Silero VAD**: Attribution note is missing WhisperWriter (see Issue 3 above). Fix as described in Issue 3.

**Flag 3 — macOS Menu Bar Recording Status Indicator**: The note reads "Always-visible recording status indicator concept inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT)." The word "concept" is appropriately hedged (VoxType's implementation is Waybar/Linux-specific JSON), but the note would be more useful if it specified what the inspiration is: e.g., "Always-visible recording status indicator concept — specifically the real-time JSON state feed powering system bar integration — inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT)." Not a blocking issue, but improves README clarity.

All markdown link formats are syntactically correct (`[ProjectName](URL)` throughout). No broken or malformed links detected.

---

## New Discovery Integration

3 items require action.

**WhisperKit (argmax-oss-swift) — SRT/VTT export credit missing**

The "SRT and VTT Export" item credits only VoxType. WhisperKit has built-in SRT/VTT subtitle export as a first-class feature (`WhisperKit` produces word-level timestamps and serialises them to SRT/VTT). This is the most direct implementation reference for Agrapha's Kotlin serializer, since WhisperKit documents the data model explicitly (word timestamps → segment grouping → SRT formatting). Both items ("SRT and VTT Export" and "JSON Export") should be reviewed; JSON export is VoxType-only and WhisperKit does not produce JSON meeting exports, so JSON attribution is correct as-is.

Recommended change for "SRT and VTT Export":
- Add `[argmax-oss-swift (WhisperKit)](https://github.com/argmaxinc/argmax-oss-swift)` to "Inspired by"
- Update attribution note to: "SRT and VTT export format design inspired by [VoxType](https://github.com/peteonrails/voxtype) (MIT); subtitle data model (word timestamps → segment grouping) noted from [WhisperKit / argmax-oss-swift](https://github.com/argmaxinc/argmax-oss-swift) (MIT)."

**Hex (kitlangton) — Parakeet engine item should credit it**

The "Parakeet ONNX Engine" item credits VoxType, Handy, and Meetily. Hex provides a clean, production Swift implementation of dual-engine Parakeet+Whisper switching with a user-facing toggle — the most direct reference design for Agrapha's planned `TranscriptionEngine` abstraction. Since Hex is MIT-licensed and macOS-only (Apple Silicon), it is a closer platform match than VoxType (Linux) or Handy (multi-platform Tauri).

Recommended change for "Parakeet ONNX Engine":
- Add `[Hex](https://github.com/kitlangton/Hex)` to "Inspired by"
- Update attribution note to include: "...and [Hex](https://github.com/kitlangton/Hex) (MIT) for the multi-engine toggle design pattern."

**noScribe — no backlog item for transcript correction editor**

noScribe ships a dedicated correction editor (noScribeEdit) for reviewing and annotating transcripts word-by-word. There is no backlog item for an inline transcript editor in Agrapha. This is a gap relative to the research findings. noScribe's GPL-3.0 license means no code reuse, but the UX pattern (click a word, correct it, re-run a segment) is freely borrowable.

Recommendation: Add a new Medium-priority backlog item — "Inline Transcript Correction Editor" — inspired by noScribe, noting GPL-3.0 code restrictions. This fits naturally between the existing transcription history feature and the re-transcription feature, and aligns with Agrapha's meeting memory use case (correcting misheard names, technical terms, proper nouns before export).

---

## Verdict

**PASS**

All 6 original issues fixed; validation complete.

All blocking and quality fixes from the original review have been applied:

1. "Parakeet ONNX Engine" promoted from Medium to High priority — requirements coverage gap for feature area 2 resolved.
2. "Parakeet ONNX Engine" — "What they do" field corrected to no longer overstate Meetily's Parakeet implementation as "ONNX Runtime" (Issue 1 fixed).
3. BlahST added to the "Global Hotkey / Dictation Mode" attribution note (Issue 2 fixed).
4. WhisperWriter added to the "Silero VAD" attribution note (Issue 3 fixed).
5. whisper-mac license problem resolved in comparable-projects.md — section renamed to "Inspiration Reference" with explicit note that it is not for attribution; plan.md attribution note updated accordingly (Issue 4 fixed).
6. WhisperWriter license updated to "Unconfirmed (no LICENSE file in repo)" in comparable-projects.md and all "(MIT)" tags for WhisperWriter replaced with "(license unconfirmed)" in plan.md.

**Count summary:**
- Attribution issues resolved: 4
- License accuracy fixes: 2 (WhisperWriter unconfirmed; whisper-mac inspiration-only)
- Requirements gaps resolved: 1 (Parakeet ONNX Engine promoted to High)
