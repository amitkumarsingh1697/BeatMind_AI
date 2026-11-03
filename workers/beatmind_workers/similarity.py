"""Similarity screen (US-029, REQ-F-52, INT-08, RISK-001): is a take a near-copy of a known recording?

Each take is fingerprinted with spectral-peak landmarks (pairs of strong peaks and the time between
them, the method popularised by Shazam) and looked up in the reference index in PostgreSQL
(``similarity_reference``, ``similarity_hash``). Many landmarks lining up at one time offset means
the take contains the same audio as a reference; the take is then withheld, refunded and sent to
the admin queue.

The fingerprint survives MP3 encoding, volume changes and excerpts. It is not meant to catch a new
performance of the same melody; that needs a melody or cover-song model (TODO.md). The index is
empty until reference recordings are loaded (``python -m beatmind_workers.reference``); which
catalogue to load is open decision TD-03.
"""

from __future__ import annotations

import subprocess
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Any

import numpy as np
import psycopg

from beatmind_workers.finishing import FFMPEG

SAMPLE_RATE = 11_025
N_FFT = 1024
HOP = 256
BANDS = (2, 12, 24, 48, 96, 192, 384, 511)  # FFT-bin band edges, roughly log-spaced
NEIGHBOURHOOD = 6  # frames either side a peak must beat in its band
FAN_OUT = 5
MAX_DT = 63  # frames (about 1.5 s)
MIN_MAGNITUDE_DB = -50.0  # relative to the loudest bin in the clip
ABSOLUTE_FLOOR_DB = -10.0  # below this a bin is silence (a full-scale sine peaks near +48 dB)

# A take is withheld when this many landmarks line up with one reference at one offset, and they
# are at least this share of the take's landmarks. Tuned on the tests in tests/test_similarity.py.
MIN_MATCHED = 25
MIN_SCORE = 0.08


@dataclass(frozen=True)
class Match:
    reference_id: int
    title: str
    matched: int
    score: float


def decode(data: bytes) -> np.ndarray:
    """Any audio ffmpeg reads, as mono float samples at 11,025 Hz."""
    proc = subprocess.run(  # noqa: S603 - fixed arguments
        [
            FFMPEG,
            "-v",
            "error",
            "-i",
            "pipe:0",
            "-f",
            "s16le",
            "-ac",
            "1",
            "-ar",
            str(SAMPLE_RATE),
            "pipe:1",
        ],  # noqa: S607
        input=data,
        capture_output=True,
        check=True,
    )
    return np.frombuffer(proc.stdout, dtype="<i2").astype(np.float32) / 32768.0


def _spectrogram(samples: np.ndarray) -> np.ndarray:
    if len(samples) < N_FFT:
        return np.zeros((0, N_FFT // 2 + 1), dtype=np.float32)
    frames = 1 + (len(samples) - N_FFT) // HOP
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(frames)[:, None]
    window = np.hanning(N_FFT).astype(np.float32)
    spec = np.abs(np.fft.rfft(samples[idx] * window, axis=1))
    return 20 * np.log10(spec + 1e-9)


def peaks(samples: np.ndarray) -> list[tuple[int, int]]:
    """(frame, bin) of the strongest bin per band that is also the band's maximum nearby in time."""
    spec = _spectrogram(samples)
    if spec.size == 0:
        return []
    floor = max(spec.max() + MIN_MAGNITUDE_DB, ABSOLUTE_FLOOR_DB)
    found: list[tuple[int, int]] = []
    for lo, hi in zip(BANDS, BANDS[1:], strict=False):
        band = spec[:, lo:hi]
        best_bin = band.argmax(axis=1)
        best = band.max(axis=1)
        # Max over a time neighbourhood, by sliding windows over a padded copy.
        padded = np.pad(best, NEIGHBOURHOOD, constant_values=-np.inf)
        windows = np.lib.stride_tricks.sliding_window_view(padded, 2 * NEIGHBOURHOOD + 1)
        local_max = windows.max(axis=1)
        keep = (best >= local_max) & (best > floor)
        for t in np.nonzero(keep)[0]:
            found.append((int(t), int(lo + best_bin[t])))
    found.sort()
    return found


def landmarks(samples: np.ndarray) -> list[tuple[int, int]]:
    """(hash, frame) pairs. hash = f1 (9 bits) | f2 (9 bits) | dt (6 bits)."""
    pts = peaks(samples)
    out: list[tuple[int, int]] = []
    for i, (t1, f1) in enumerate(pts):
        paired = 0
        for t2, f2 in pts[i + 1 :]:
            dt = t2 - t1
            if dt < 1:
                continue
            if dt > MAX_DT or paired >= FAN_OUT:
                break
            out.append(((f1 << 15) | (f2 << 6) | dt, t1))
            paired += 1
    return out


def fingerprint(data: bytes) -> tuple[list[tuple[int, int]], float]:
    samples = decode(data)
    return landmarks(samples), len(samples) / SAMPLE_RATE


def add_reference(conn: psycopg.Connection, data: bytes, title: str, artist: str | None, source: str) -> int:
    """Indexes a known recording. Returns its reference id."""
    marks, seconds = fingerprint(data)
    if not marks:
        raise ValueError("no landmarks found: is the file silent or shorter than a second?")
    ref_id = conn.execute(
        "INSERT INTO similarity_reference (title, artist, source, seconds, hashes)"
        " VALUES (%s, %s, %s, %s, %s) RETURNING id",
        (title, artist, source, seconds, len(marks)),
    ).fetchone()[0]
    with conn.cursor().copy("COPY similarity_hash (hash, reference_id, t) FROM STDIN") as copy:
        for h, t in marks:
            copy.write_row((h, ref_id, t))
    conn.commit()
    return int(ref_id)


class Screen:
    """Looks takes up in the reference index."""

    def __init__(
        self, conn: psycopg.Connection, min_matched: int = MIN_MATCHED, min_score: float = MIN_SCORE
    ):
        self.conn = conn
        self.min_matched = min_matched
        self.min_score = min_score

    def best_match(self, marks: list[tuple[int, int]]) -> Match | None:
        if not marks:
            return None
        times: dict[int, list[int]] = defaultdict(list)
        for h, t in marks:
            times[h].append(t)
        rows = self.conn.execute(
            "SELECT hash, reference_id, t FROM similarity_hash WHERE hash = ANY(%s)", (list(times),)
        ).fetchall()
        self.conn.commit()
        votes: Counter[tuple[int, int]] = Counter()
        for h, ref, t_ref in rows:
            for t_query in times[h]:
                votes[(ref, t_ref - t_query)] += 1
        if not votes:
            return None
        # Neighbouring offsets count together: frames can shift by one after re-encoding.
        merged: Counter[tuple[int, int]] = Counter()
        for (ref, offset), n in votes.items():
            for d in (-1, 0, 1):
                merged[(ref, offset + d)] += n
        (ref, _), matched = merged.most_common(1)[0]
        title = self.conn.execute("SELECT title FROM similarity_reference WHERE id = %s", (ref,)).fetchone()
        self.conn.commit()
        return Match(int(ref), title[0] if title else "", int(matched), min(1.0, matched / len(marks)))

    def check(self, data: bytes) -> dict[str, Any]:
        """The screen result stored with the take. ``withheld`` is true for a close match."""
        marks, _ = fingerprint(data)
        match = self.best_match(marks)
        result: dict[str, Any] = {"checked": True, "landmarks": len(marks), "score": 0.0, "matched": 0}
        if match is not None:
            result.update(
                score=round(match.score, 4),
                matched=match.matched,
                referenceId=match.reference_id,
                referenceTitle=match.title,
            )
            result["withheld"] = match.matched >= self.min_matched and match.score >= self.min_score
        else:
            result["withheld"] = False
        return result
