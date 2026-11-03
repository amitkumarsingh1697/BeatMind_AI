"""US-040: the G1 kit keeps listeners blind and scores answers against the PRD pass mark."""

import csv
import random

import pytest

from beatmind_workers import g1


def write_clips(path, languages=("hi",), prompts=20):
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["language", "prompt_id", "system", "file"])
        for lang in languages:
            for p in range(1, prompts + 1):
                w.writerow([lang, f"p{p:02d}", "beatmind", f"{lang}/p{p:02d}-bm.mp3"])
                w.writerow([lang, f"p{p:02d}", "competitor", f"{lang}/p{p:02d}-c.mp3"])


def answer(tmp_path, key_path, win_rate, tie_rate=0.0, seed=1):
    rng = random.Random(seed)
    with key_path.open(newline="", encoding="utf-8") as f:
        key = list(csv.DictReader(f))
    out = tmp_path / "answers.csv"
    with out.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["listener", "pair_id", "choice", "pronunciation"])
        for row in key:
            r = rng.random()
            if r < tie_rate:
                choice = "tie"
            elif r < tie_rate + win_rate:
                choice = row["beatmind_side"]
            else:
                choice = "B" if row["beatmind_side"] == "A" else "A"
            w.writerow([row["listener"], row["pair_id"], choice, 4])
    return out


def test_sheets_are_blind_and_balanced(tmp_path):
    clips = tmp_path / "clips.csv"
    write_clips(clips, languages=("hi", "en"))
    sheets = g1.plan(clips, 30, tmp_path / "run", seed=7)
    assert len(sheets) == 60
    header = sheets[0].read_text(encoding="utf-8").splitlines()[0]
    assert "beatmind" not in header.lower() and "system" not in header.lower()
    with (tmp_path / "run" / "key.csv").open(encoding="utf-8") as f:
        key = list(csv.DictReader(f))
    assert len(key) == 60 * 20
    sides = [k["beatmind_side"] for k in key]
    assert 0.4 < sides.count("A") / len(sides) < 0.6
    # Same seed, same plan.
    g1.plan(clips, 30, tmp_path / "again", seed=7)
    assert (tmp_path / "again" / "key.csv").read_text() == (tmp_path / "run" / "key.csv").read_text()


def test_passes_with_30_listeners_at_the_mark(tmp_path):
    clips = tmp_path / "clips.csv"
    write_clips(clips)
    g1.plan(clips, 30, tmp_path / "run", seed=3)
    answers = answer(tmp_path, tmp_path / "run" / "key.csv", win_rate=0.35, tie_rate=0.15)
    [r] = g1.analyse(tmp_path / "run" / "key.csv", answers)
    assert r.listeners == 30 and r.pairings == 600
    assert r.share >= 0.40 and r.passed
    assert r.low < r.share < r.high
    assert r.pronunciation == 4
    assert "BEATMIND_G1_PASSED=hi" in g1.report([r])


def test_fails_below_the_share_or_with_too_few_listeners(tmp_path):
    clips = tmp_path / "clips.csv"
    write_clips(clips)
    g1.plan(clips, 30, tmp_path / "run", seed=3)
    [weak] = g1.analyse(tmp_path / "run" / "key.csv", answer(tmp_path, tmp_path / "run" / "key.csv", 0.2))
    assert not weak.passed and "needs 40%" in weak.reason

    g1.plan(clips, 12, tmp_path / "small", seed=3)
    [few] = g1.analyse(tmp_path / "small" / "key.csv", answer(tmp_path, tmp_path / "small" / "key.csv", 0.9))
    assert not few.passed and "needs 30" in few.reason


def test_wilson_interval_matches_a_known_value():
    low, high = g1.wilson(40, 100)
    assert low == pytest.approx(0.3094, abs=1e-3)
    assert high == pytest.approx(0.4980, abs=1e-3)


def test_bad_input_is_refused(tmp_path):
    clips = tmp_path / "clips.csv"
    clips.write_text("language,prompt_id,system,file\nhi,p1,beatmind,a.mp3\n", encoding="utf-8")
    with pytest.raises(ValueError, match="needs one beatmind and one competitor"):
        g1.read_clips(clips)
