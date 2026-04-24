"""diarize_session.py - speaker diarization sidecar for meeting-notes app."""

import argparse
import json
import os
import sys


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run pyannote speaker diarization on a WAV file and write JSON output."
    )
    parser.add_argument("--audio", required=True, help="Path to the audio file to diarize")
    parser.add_argument("--out", required=True, help="Path to write JSON output")
    parser.add_argument("--hf-token", default=None, help="Hugging Face API token (falls back to env var)")
    parser.add_argument("--max-speakers", type=int, default=None, help="Maximum number of speakers to detect")
    args = parser.parse_args()

    audio_path: str = args.audio
    out_path: str = args.out
    max_speakers: int | None = args.max_speakers

    # Resolve token: CLI arg takes precedence over environment variable
    token_env_key = "HF_TOKEN"
    hf_token: str | None = args.hf_token or os.environ.get(token_env_key)
    if not hf_token:
        print("[diarize] ERROR: No Hugging Face token provided. Use --hf-token or set HF_TOKEN env var.", file=sys.stderr)
        sys.exit(3)

    # Guard: ensure pyannote.audio is available
    try:
        from pyannote.audio import Pipeline  # type: ignore[import]
    except ImportError as exc:
        print(f"[diarize] ERROR: pyannote.audio is not installed: {exc}", file=sys.stderr)
        sys.exit(2)

    print(f"[diarize] Loading pipeline for {audio_path}", file=sys.stderr)

    # Load the diarization pipeline
    try:
        pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-community-1",
            use_auth_token=hf_token,
        )
    except ValueError as exc:
        print(f"[diarize] ERROR: Invalid Hugging Face token: {exc}", file=sys.stderr)
        sys.exit(3)
    except Exception as exc:
        print(f"[diarize] ERROR: Failed to load pipeline: {exc}", file=sys.stderr)
        sys.exit(2)

    # Tune segmentation to handle short speech segments
    pipeline._segmentation.min_duration_on = 0.5

    print(f"[diarize] Processing {audio_path}", file=sys.stderr)

    # Run diarization
    diarization_kwargs: dict = {"max_speakers": max_speakers} if max_speakers is not None else {}
    diarization = pipeline(audio_path, **diarization_kwargs)

    # Convert pyannote Annotation to a plain list of dicts
    segments = [
        {
            "start": round(turn.start, 3),
            "end": round(turn.end, 3),
            "speaker": speaker,
        }
        for turn, _, speaker in diarization.itertracks(yield_label=True)
    ]

    if not segments:
        print("[diarize] ERROR: Diarization produced no segments.", file=sys.stderr)
        sys.exit(5)

    print(f"[diarize] Writing {len(segments)} segment(s) to {out_path}", file=sys.stderr)

    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(segments, fh, ensure_ascii=False, indent=2)

    print("[diarize] Done.", file=sys.stderr)
    sys.exit(0)


if __name__ == "__main__":
    main()
