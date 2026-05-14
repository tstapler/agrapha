#!/usr/bin/env python3
"""
Download sample audio + reference transcript for WerBaselineTest.

Usage:
    python3 scripts/download_wer_data.py            # quick smoke-test (jfk, ~11s)
    python3 scripts/download_wer_data.py --ami       # realistic meeting speech (AMI corpus)
    python3 scripts/download_wer_data.py --ami --n 5 # download 5 AMI segments, concatenated

Output directory (override with --out):
    ~/.local/share/meeting-notes/wer-test/
        audio.wav       16kHz mono PCM WAV
        reference.txt   verbatim ground-truth transcript

Run the harness after downloading:
    ./gradlew :composeApp:desktopTest \\
        --tests "com.meetingnotes.transcription.WerBaselineTest"

Requirements for --ami:
    pip install "datasets<3.0" soundfile numpy librosa

    datasets<3.0 requires Python <=3.12 (dill incompatibility with Python 3.14).
    Use: uv run --python 3.12 --with "datasets<3.0" --with soundfile --with numpy --with librosa python3 scripts/download_wer_data.py --ami

Datasets used:
    jfk.wav    — whisper.cpp sample, public domain
    AMI corpus — edinburghcstr/ami on HuggingFace, CC BY 4.0
                 https://groups.inf.ed.ac.uk/ami/corpus/license.shtml
"""

import argparse
import pathlib
import sys
import urllib.request


DEFAULT_OUT = pathlib.Path.home() / ".local/share/meeting-notes/wer-test"

JFK_URL = "https://github.com/ggml-org/whisper.cpp/raw/master/samples/jfk.wav"
JFK_TRANSCRIPT = (
    "And so my fellow Americans, ask not what your country can do for you, "
    "ask what you can do for your country."
)


def download_jfk(out_dir: pathlib.Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    wav_path = out_dir / "audio.wav"
    ref_path = out_dir / "reference.txt"

    print(f"Downloading JFK sample from whisper.cpp …")
    urllib.request.urlretrieve(JFK_URL, wav_path)
    ref_path.write_text(JFK_TRANSCRIPT)

    print(f"  audio      → {wav_path}  ({wav_path.stat().st_size // 1024} KB)")
    print(f"  transcript → {ref_path}")
    print(f"  text       : {JFK_TRANSCRIPT}")
    print()
    print("Note: 11-second clean oratory — good for pipeline smoke-testing,")
    print("      not representative of meeting/conversational speech.")
    print("      Use --ami for a realistic meeting benchmark.")


def download_ami(out_dir: pathlib.Path, n_segments: int) -> None:
    try:
        from datasets import load_dataset
    except ImportError:
        print("ERROR: 'datasets' package not found.", file=sys.stderr)
        print('Install it with:  pip install "datasets<3.0" soundfile numpy librosa', file=sys.stderr)
        print("Note: requires Python <=3.12. Use: uv run --python 3.12 --with ...", file=sys.stderr)
        sys.exit(1)

    try:
        import soundfile as sf
        import numpy as np
    except ImportError:
        print("ERROR: 'soundfile' or 'numpy' package not found.", file=sys.stderr)
        print("Install it with:  pip install soundfile numpy", file=sys.stderr)
        sys.exit(1)

    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Streaming {n_segments} segment(s) from edinburghcstr/ami (IHM, test split) …")
    print("(First run downloads model metadata — ~100 MB, cached afterwards)")
    print()

    ds = load_dataset(
        "edinburghcstr/ami",
        "ihm",
        split="test",
        streaming=True,
    )

    arrays = []
    transcripts = []
    sample_rate = None
    collected = 0

    for sample in ds:
        audio = sample["audio"]
        text = sample["text"].strip()
        if not text:
            continue

        sr = audio["sampling_rate"]
        arr = np.array(audio["array"], dtype=np.float32)

        # Resample to 16kHz if needed (AMI IHM is already 16kHz, but be safe)
        if sr != 16_000:
            try:
                import resampy
                arr = resampy.resample(arr, sr, 16_000)
                sr = 16_000
            except ImportError:
                print(
                    f"WARNING: segment sample rate is {sr} Hz but resampy is not installed. "
                    "Saving at original rate — the WER test expects 16kHz.",
                    file=sys.stderr,
                )

        sample_rate = sr
        arrays.append(arr)
        transcripts.append(text)
        collected += 1

        duration = len(arr) / sr
        word_count = len(text.split())
        preview = text[:70] + ("…" if len(text) > 70 else "")
        print(f"  [{collected}/{n_segments}] {duration:.1f}s  {word_count} words  \"{preview}\"")

        if collected >= n_segments:
            break

    if not arrays:
        print("ERROR: no segments retrieved from AMI dataset.", file=sys.stderr)
        sys.exit(1)

    # Concatenate segments with 0.5s silence gap between them
    silence_samples = int(0.5 * sample_rate)
    silence = np.zeros(silence_samples, dtype=np.float32)
    audio_out = np.concatenate(
        [chunk for pair in zip(arrays, [silence] * len(arrays)) for chunk in pair][:-1]
    )

    wav_path = out_dir / "audio.wav"
    ref_path = out_dir / "reference.txt"

    sf.write(str(wav_path), audio_out, sample_rate, subtype="PCM_16")
    full_transcript = " ".join(transcripts)
    ref_path.write_text(full_transcript)

    total_duration = len(audio_out) / sample_rate
    word_count = len(full_transcript.split())
    print()
    print(f"Written {n_segments} segment(s), {total_duration:.1f}s total, {word_count} words.")
    print(f"  audio      → {wav_path}  ({wav_path.stat().st_size // 1024} KB)")
    print(f"  transcript → {ref_path}")
    print()
    print("AMI corpus licence: CC BY 4.0")
    print("https://groups.inf.ed.ac.uk/ami/corpus/license.shtml")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Download WER benchmark data for WerBaselineTest."
    )
    parser.add_argument(
        "--ami",
        action="store_true",
        help='Download from AMI Meeting Corpus (realistic meeting speech, CC BY 4.0). '
             'Requires: pip install "datasets<3.0" soundfile numpy',
    )
    parser.add_argument(
        "--n",
        type=int,
        default=1,
        metavar="N",
        help="Number of AMI segments to concatenate (default: 1). Only used with --ami.",
    )
    parser.add_argument(
        "--out",
        type=pathlib.Path,
        default=DEFAULT_OUT,
        metavar="DIR",
        help=f"Output directory (default: {DEFAULT_OUT})",
    )
    args = parser.parse_args()

    if args.ami:
        download_ami(args.out, args.n)
    else:
        download_jfk(args.out)

    print()
    print("Run the WER harness:")
    print(
        "  ./gradlew :composeApp:desktopTest "
        "--tests \"com.meetingnotes.transcription.WerBaselineTest\""
    )


if __name__ == "__main__":
    main()
