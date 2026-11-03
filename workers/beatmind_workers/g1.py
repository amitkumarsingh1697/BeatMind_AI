"""G1 listening test kit (US-040, EXP-03, PRD gate G1).

Gate G1: a language launches when at least 30 native listeners have compared BeatMind with the best
competitor for creator background music, blind, and BeatMind was preferred or tied in at least 40%
of the pairings. Until then the app marks the language experimental (REQ-F-07); after a pass, add it
to ``BEATMIND_G1_PASSED``.

Two commands:

``plan``
    Reads a clips file (``language,prompt_id,system,file`` with system ``beatmind`` or
    ``competitor``) and a listener count per language. Writes one answer sheet per listener, with
    the pairs in a random order and BeatMind on a random side, and a separate key file that says
    which side was which. Listeners see only "A" and "B". The seed makes the plan reproducible.

``analyse``
    Reads the key and the collected answers (``listener,pair_id,choice,pronunciation`` with choice
    ``A``, ``B`` or ``tie``, pronunciation 1 to 5 for the BeatMind clip) and writes the report: per
    language, the number of listeners, the share of pairings where BeatMind was preferred or tied
    with a 95% Wilson interval, the listener-level share, mean pronunciation, and the verdict.

The interval treats pairings as independent, which overstates certainty a little because each
listener answers many pairs; the listener-level share is reported next to it as a check. Both are
shown, the verdict uses the PRD rule as written.

Usage::

    python -m beatmind_workers.g1 plan clips.csv --listeners 30 --out g1-run/ --seed 2026
    python -m beatmind_workers.g1 analyse g1-run/key.csv answers.csv --out g1-run/report.md
"""

from __future__ import annotations

import argparse
import csv
import math
import random
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

MIN_LISTENERS = 30
PASS_SHARE = 0.40
Z95 = 1.959964


@dataclass(frozen=True)
class Clip:
    language: str
    prompt_id: str
    system: str
    file: str


@dataclass(frozen=True)
class Result:
    language: str
    listeners: int
    pairings: int
    preferred: int
    tied: int
    share: float
    low: float
    high: float
    listener_share: float
    pronunciation: float | None
    passed: bool
    reason: str


def wilson(successes: int, n: int, z: float = Z95) -> tuple[float, float]:
    """95% Wilson score interval for a proportion."""
    if n == 0:
        return 0.0, 1.0
    p = successes / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, centre - half), min(1.0, centre + half)


def read_clips(path: Path) -> dict[str, dict[str, dict[str, str]]]:
    """language -> prompt_id -> system -> file. Every prompt needs one clip from each system."""
    clips: dict[str, dict[str, dict[str, str]]] = defaultdict(lambda: defaultdict(dict))
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            system = row["system"].strip().lower()
            if system not in ("beatmind", "competitor"):
                raise ValueError(f"system must be beatmind or competitor, got {row['system']!r}")
            clips[row["language"].strip()][row["prompt_id"].strip()][system] = row["file"].strip()
    for language, prompts in clips.items():
        for prompt_id, systems in prompts.items():
            if set(systems) != {"beatmind", "competitor"}:
                raise ValueError(f"{language}/{prompt_id} needs one beatmind and one competitor clip")
    return clips


def plan(clips_path: Path, listeners: int, out: Path, seed: int) -> list[Path]:
    """Writes answer sheets and the key. Returns the sheet paths."""
    clips = read_clips(clips_path)
    rng = random.Random(seed)  # noqa: S311 - reproducible test plan, not security
    out.mkdir(parents=True, exist_ok=True)
    sheets: list[Path] = []
    key_rows: list[dict[str, str]] = []
    for language in sorted(clips):
        prompts = sorted(clips[language])
        for n in range(1, listeners + 1):
            listener = f"{language}-{n:03d}"
            order = prompts[:]
            rng.shuffle(order)
            sheet_rows = []
            for i, prompt_id in enumerate(order, start=1):
                pair_id = f"{listener}-{i:02d}"
                beatmind_side = rng.choice("AB")
                files = clips[language][prompt_id]
                a = files["beatmind"] if beatmind_side == "A" else files["competitor"]
                b = files["competitor"] if beatmind_side == "A" else files["beatmind"]
                sheet_rows.append(
                    {"pair_id": pair_id, "clip_a": a, "clip_b": b, "choice": "", "pronunciation": ""}
                )
                key_rows.append(
                    {
                        "pair_id": pair_id,
                        "listener": listener,
                        "language": language,
                        "prompt_id": prompt_id,
                        "beatmind_side": beatmind_side,
                    }
                )
            sheet = out / "sheets" / f"{listener}.csv"
            sheet.parent.mkdir(exist_ok=True)
            with sheet.open("w", newline="", encoding="utf-8") as f:
                w = csv.DictWriter(f, fieldnames=["pair_id", "clip_a", "clip_b", "choice", "pronunciation"])
                w.writeheader()
                w.writerows(sheet_rows)
            sheets.append(sheet)
    with (out / "key.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["pair_id", "listener", "language", "prompt_id", "beatmind_side"])
        w.writeheader()
        w.writerows(key_rows)
    return sheets


def analyse(key_path: Path, answers_path: Path) -> list[Result]:
    with key_path.open(newline="", encoding="utf-8") as f:
        key = {row["pair_id"]: row for row in csv.DictReader(f)}
    by_language: dict[str, list[tuple[str, str, int | None]]] = defaultdict(list)
    with answers_path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            pair = key.get(row["pair_id"].strip())
            if pair is None:
                raise ValueError(f"unknown pair {row['pair_id']!r}")
            choice = row["choice"].strip().upper()
            if choice not in ("A", "B", "TIE"):
                raise ValueError(f"{row['pair_id']}: choice must be A, B or tie")
            outcome = "tie" if choice == "TIE" else ("win" if choice == pair["beatmind_side"] else "loss")
            raw = (row.get("pronunciation") or "").strip()
            score = int(raw) if raw else None
            if score is not None and not 1 <= score <= 5:
                raise ValueError(f"{row['pair_id']}: pronunciation must be 1 to 5")
            by_language[pair["language"]].append((pair["listener"], outcome, score))

    results = []
    for language in sorted(by_language):
        rows = by_language[language]
        listeners = sorted({r[0] for r in rows})
        preferred = sum(1 for r in rows if r[1] == "win")
        tied = sum(1 for r in rows if r[1] == "tie")
        n = len(rows)
        ok = preferred + tied
        low, high = wilson(ok, n)
        per_listener = []
        for listener in listeners:
            mine = [r for r in rows if r[0] == listener]
            per_listener.append(sum(1 for r in mine if r[1] != "loss") / len(mine))
        scores = [r[2] for r in rows if r[2] is not None]
        share = ok / n
        if len(listeners) < MIN_LISTENERS:
            passed, reason = False, f"only {len(listeners)} listeners; G1 needs {MIN_LISTENERS}"
        elif share < PASS_SHARE:
            passed, reason = False, f"preferred or tied in {share:.0%}; G1 needs {PASS_SHARE:.0%}"
        else:
            passed, reason = True, f"{len(listeners)} listeners, preferred or tied in {share:.0%}"
        results.append(
            Result(
                language=language,
                listeners=len(listeners),
                pairings=n,
                preferred=preferred,
                tied=tied,
                share=share,
                low=low,
                high=high,
                listener_share=sum(per_listener) / len(per_listener),
                pronunciation=sum(scores) / len(scores) if scores else None,
                passed=passed,
                reason=reason,
            )
        )
    return results


def report(results: list[Result]) -> str:
    lines = [
        "# G1 listening test report",
        "",
        f"Pass mark (PRD gate G1): at least {MIN_LISTENERS} native listeners, BeatMind preferred or tied in "
        f"at least {PASS_SHARE:.0%} of pairings.",
        "",
        "| Language | Listeners | Pairings | Preferred | Tied | Preferred or tied | 95% interval "
        "| Per-listener mean | Pronunciation (1-5) | G1 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in results:
        pron = f"{r.pronunciation:.2f}" if r.pronunciation is not None else "not rated"
        lines.append(
            f"| {r.language} | {r.listeners} | {r.pairings} | {r.preferred} | {r.tied} | {r.share:.1%} "
            f"| {r.low:.1%} to {r.high:.1%} | {r.listener_share:.1%} | {pron} | "
            f"{'Pass' if r.passed else 'Not passed'} |"
        )
    lines += ["", "## Verdicts", ""]
    lines += [f"- **{r.language}:** {'pass' if r.passed else 'not passed'}, {r.reason}." for r in results]
    passed = [r.language for r in results if r.passed]
    lines += [
        "",
        "## Next step",
        "",
        (
            f"Set `BEATMIND_G1_PASSED={','.join(passed)}` on the API so these languages lose the "
            "experimental label (REQ-F-07), and record the decision in the decision log."
            if passed
            else "No language passed. Keep every language marked experimental (REQ-F-07)."
        ),
        "",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="beatmind_workers.g1", description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan", help="write blind answer sheets and the key")
    p.add_argument("clips", type=Path)
    p.add_argument("--listeners", type=int, default=MIN_LISTENERS)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--seed", type=int, default=2026)
    a = sub.add_parser("analyse", help="score the answers against the key")
    a.add_argument("key", type=Path)
    a.add_argument("answers", type=Path)
    a.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    if args.command == "plan":
        sheets = plan(args.clips, args.listeners, args.out, args.seed)
        key = args.out / "key.csv"
        print(f"Wrote {len(sheets)} answer sheets and {key}. Keep the key away from listeners.")
    else:
        text = report(analyse(args.key, args.answers))
        if args.out:
            args.out.write_text(text, encoding="utf-8")
        print(text)


if __name__ == "__main__":
    main()
