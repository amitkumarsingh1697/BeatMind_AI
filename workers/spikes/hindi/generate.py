#!/usr/bin/env python3
"""US-006 step 1: generate one take per spike prompt and build blind scoring sheets.

Run on the GPU machine while `uv run acestep-api` is serving:

    python3 generate.py --out run1 --listeners 3

Audio files are renamed to random codes so listeners cannot tell which prompt or language they
belong to. key.csv maps codes back to prompts; do not share it with listeners.
"""

from __future__ import annotations

import argparse
import csv
import random
import secrets
import sys
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parents[1] / "bench"))
from acestep_client import AceStepClient  # noqa: E402

SHEET_COLUMNS = ["code", "pronunciation_1to5", "musicality_1to5", "usable_for_creator_video_yes_no", "notes"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", default="http://localhost:8001")
    ap.add_argument("--api-key", default="")
    ap.add_argument("--out", required=True)
    ap.add_argument("--listeners", type=int, default=3)
    args = ap.parse_args()

    out = Path(args.out)
    (out / "audio").mkdir(parents=True, exist_ok=True)
    client = AceStepClient(args.api, args.api_key)
    key_rows = []
    lyric_rows = []
    for set_name in ("hindi", "hinglish"):
        with open(HERE / f"prompts-{set_name}.csv", newline="", encoding="utf-8") as f:
            for p in csv.DictReader(f):
                r = client.generate(
                    {
                        "prompt": p["prompt"],
                        "lyrics": p["lyrics"],
                        "vocal_language": p["vocal_language"],
                        "audio_duration": float(p["duration"]),
                        "batch_size": 1,
                        "audio_format": "mp3",
                    }
                )
                if not r.ok:
                    print(f"{p['id']}: FAILED ({r.error})")
                    continue
                code = secrets.token_hex(3)
                client.download(r.files[0], str(out / "audio" / f"{code}.mp3"))
                key_rows.append(
                    {"code": code, "set": set_name, "prompt_id": p["id"], "seconds": round(r.seconds, 1)}
                )
                lyric_rows.append({"code": code, "lyrics": p["lyrics"]})
                print(f"{p['id']}: ok -> {code}.mp3")

    with open(out / "key.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(key_rows[0]))
        writer.writeheader()
        writer.writerows(key_rows)

    # Listeners need the intended words to judge pronunciation; this sheet has no prompt IDs.
    with open(out / "lyrics-sheet.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["code", "lyrics"])
        writer.writeheader()
        writer.writerows(sorted(lyric_rows, key=lambda r: r["code"]))

    for n in range(1, args.listeners + 1):
        codes = [k["code"] for k in key_rows]
        random.shuffle(codes)
        with open(out / f"sheet-listener-{n}.csv", "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(SHEET_COLUMNS)
            for code in codes:
                writer.writerow([code, "", "", "", ""])
    print("Done. Give each listener the audio folder, lyrics-sheet.csv and their own sheet-listener-N.csv.")


if __name__ == "__main__":
    main()
