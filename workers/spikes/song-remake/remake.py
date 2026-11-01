#!/usr/bin/env python3
"""Remake Raat Ka Safar v3 on a GPU with the settings from the song-quality diagnosis.

Run while `uv run acestep-api` is serving with a base or SFT DiT and the 1.7B LM (see README.md):

    python3 remake.py --out remake --takes 6
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parents[1] / "bench"))
from acestep_client import AceStepClient  # noqa: E402

# Written in the descriptive style the 5Hz LM uses for its own captions, with an energy map.
CAPTION = (
    "A romantic Hindi ghazal fused with hard-hitting hip-hop, at 85 bpm in G minor. It opens with "
    "soft harmonium and piano and no drums. A soulful male ghazal singer enters over tabla and deep "
    "sub bass, singing long held notes with melismatic ornaments that rise in pitch into the chorus."
    " The chorus opens up with fuller drums and a higher, more powerful vocal. Then the beat "
    "switches to punchy boom bap drums with a heavy kick, crisp double-time hi-hats and a snare on "
    "two and four, and a confident male rapper delivers a fast, rhythmic, aggressive flow locked "
    "tightly to the beat. The second ghazal verse drops back to an intimate, quiet vocal, the final "
    "chorus hits at full energy, the second rap verse is even faster and more intense, and the song "
    "ends on soft harmonium. Strong dynamics between sections, not a flat loop."
)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", default="http://localhost:8001")
    ap.add_argument("--out", required=True)
    ap.add_argument("--takes", type=int, default=6)
    ap.add_argument(
        "--steps", type=int, default=50, help="inference steps; base and SFT models want 32 to 64"
    )
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    lyrics = (HERE / "raat-ka-safar-v3-lyrics.txt").read_text(encoding="utf-8")
    client = AceStepClient(args.api, timeout=3600)
    for i in range(1, args.takes + 1):
        r = client.generate(
            {
                "prompt": CAPTION,
                "lyrics": lyrics,
                "vocal_language": "hi",
                "audio_duration": 150.0,
                "bpm": 85,
                "key_scale": "G minor",
                "time_signature": "4",
                # Without these the LM rewrites the caption and guesses the language.
                "use_cot_caption": False,
                "use_cot_language": False,
                "thinking": True,
                "inference_steps": args.steps,
                "batch_size": 1,
                "audio_format": "wav",
            }
        )
        if not r.ok:
            print(f"take{i}: FAILED ({r.error})")
            continue
        client.download(r.files[0], str(out / f"take{i}.wav"))
        print(f"take{i}: ok in {r.seconds:.0f} s")


if __name__ == "__main__":
    main()
