#!/usr/bin/env python3
"""US-006 step 2: combine the listeners' sheets and make the go or fallback call.

    python3 score.py run1

Pass marks are proposals [H] and must be agreed before the sheets are read (validation plan,
section 1): a language set passes when its mean pronunciation score is at least 3.5 out of 5 AND
at least 60% of takes are judged usable for a creator video by most listeners. A set that fails
falls back to the Lyria API for that language until the full G1 test (A-021, RISK-003).
"""

from __future__ import annotations

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

PASS_PRONUNCIATION = 3.5
PASS_USABLE_SHARE = 0.6


def summarise(run: Path) -> dict[str, dict[str, float | bool | int]]:
    with open(run / "key.csv", newline="", encoding="utf-8") as f:
        set_of = {row["code"]: row["set"] for row in csv.DictReader(f)}

    pron: dict[str, list[int]] = defaultdict(list)
    music: dict[str, list[int]] = defaultdict(list)
    votes: dict[str, list[bool]] = defaultdict(list)
    sheets = sorted(run.glob("sheet-listener-*.csv"))
    if not sheets:
        raise SystemExit(f"no sheet-listener-*.csv files in {run}")
    for sheet in sheets:
        with open(sheet, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                code = row["code"]
                if not row["pronunciation_1to5"]:
                    raise SystemExit(f"{sheet.name}: {code} has no score; every row must be filled in")
                pron[code].append(int(row["pronunciation_1to5"]))
                music[code].append(int(row["musicality_1to5"]))
                votes[code].append(
                    row["usable_for_creator_video_yes_no"].strip().lower() in ("yes", "y", "haan")
                )

    result: dict[str, dict[str, float | bool | int]] = {}
    for set_name in sorted(set(set_of.values())):
        codes = [c for c, s in set_of.items() if s == set_name]
        mean_pron = statistics.mean(statistics.mean(pron[c]) for c in codes)
        mean_music = statistics.mean(statistics.mean(music[c]) for c in codes)
        usable = sum(1 for c in codes if sum(votes[c]) > len(votes[c]) / 2) / len(codes)
        result[set_name] = {
            "takes": len(codes),
            "listeners": len(sheets),
            "mean_pronunciation": round(mean_pron, 2),
            "mean_musicality": round(mean_music, 2),
            "usable_share": round(usable, 2),
            "go": mean_pron >= PASS_PRONUNCIATION and usable >= PASS_USABLE_SHARE,
        }
    return result


def main() -> None:
    run = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    for set_name, s in summarise(run).items():
        verdict = "GO" if s["go"] else "FALLBACK (Lyria API for this language)"
        print(
            f"{set_name}: {verdict} | pronunciation {s['mean_pronunciation']}/5, "
            f"musicality {s['mean_musicality']}/5, usable {s['usable_share']:.0%} "
            f"({s['takes']} takes, {s['listeners']} listeners)"
        )


if __name__ == "__main__":
    main()
