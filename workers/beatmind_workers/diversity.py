"""Are the takes of one request really different songs? (two credits buy two compositions)

Different file names or hashes are not enough: in the 4 Nov RCA both takes had different bytes and
uncorrelated waveforms, yet sounded like one song, because they shared one plan. This module
compares every pair of takes on what a listener hears:

* ``waveform``: correlation of the samples. Near 1 means the same recording (a duplicate file or a
  repeated seed).
* ``harmony``: correlation of the chroma sequences (which notes sound, second by second), at the
  best time offset within ``MAX_LAG_SECONDS``. High means the same chords and melody line in the
  same order, which is what made the RCA takes sound alike.
* ``keyProfile``: cosine of the average chroma. Reported for information only: two songs in the
  same key score high on it.

A take is a duplicate when the waveform or the harmony score reaches its threshold. The thresholds
come from the takes in the RCA evidence on the real model (see ``tests/test_diversity.py`` and the
pull request); they are meant to be tuned as we gather more real takes.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

import numpy as np

from beatmind_workers.similarity import SAMPLE_RATE, decode

N_FFT = 4096
HOP = 2048  # about 0.19 s at 11,025 Hz
FRAMES_PER_SECOND = SAMPLE_RATE / HOP
MIN_HZ, MAX_HZ = 55.0, 2000.0
MAX_LAG_SECONDS = 20
SEGMENT_SECONDS = 1.0

WAVEFORM_DUPLICATE = 0.9
HARMONY_DUPLICATE = 0.8


@dataclass(frozen=True)
class Comparison:
    waveform: float
    harmony: float
    key_profile: float
    same_bytes: bool

    @property
    def duplicate(self) -> bool:
        return self.same_bytes or self.waveform >= WAVEFORM_DUPLICATE or self.harmony >= HARMONY_DUPLICATE

    def as_dict(self) -> dict:
        return {
            "waveform": round(self.waveform, 4),
            "harmony": round(self.harmony, 4),
            "keyProfile": round(self.key_profile, 4),
            "sameBytes": self.same_bytes,
            "duplicate": self.duplicate,
        }


def chroma(samples: np.ndarray) -> np.ndarray:
    """One 12-note chroma vector per second of audio, shape (seconds, 12)."""
    if len(samples) < N_FFT:
        return np.zeros((0, 12), dtype=np.float32)
    frames = 1 + (len(samples) - N_FFT) // HOP
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(frames)[:, None]
    window = np.hanning(N_FFT).astype(np.float32)
    power = np.abs(np.fft.rfft(samples[idx] * window, axis=1)) ** 2
    freqs = np.fft.rfftfreq(N_FFT, 1 / SAMPLE_RATE)
    band = (freqs >= MIN_HZ) & (freqs <= MAX_HZ)
    pitch_class = (np.round(12 * np.log2(freqs[band] / 440.0)).astype(int) + 9) % 12
    per_frame = np.zeros((frames, 12), dtype=np.float64)
    for pc in range(12):
        per_frame[:, pc] = power[:, band][:, pitch_class == pc].sum(axis=1)
    per_frame = np.log1p(per_frame)
    step = max(1, int(round(FRAMES_PER_SECOND * SEGMENT_SECONDS)))
    seconds = frames // step
    if seconds == 0:
        return np.zeros((0, 12), dtype=np.float32)
    return per_frame[: seconds * step].reshape(seconds, step, 12).mean(axis=1).astype(np.float32)


def _centred(c: np.ndarray) -> np.ndarray:
    c = c - c.mean(axis=1, keepdims=True)
    norm = np.linalg.norm(c, axis=1, keepdims=True)
    return np.divide(c, norm, out=np.zeros_like(c), where=norm > 1e-9)


def harmony(a: np.ndarray, b: np.ndarray) -> float:
    """Best mean correlation of the per-second chroma of ``a`` and ``b`` over time offsets."""
    ca, cb = _centred(a), _centred(b)
    if len(ca) == 0 or len(cb) == 0:
        return 0.0
    best = -1.0
    for lag in range(-MAX_LAG_SECONDS, MAX_LAG_SECONDS + 1):
        x = ca[max(0, lag) :]
        y = cb[max(0, -lag) :]
        n = min(len(x), len(y))
        if n < max(5, min(len(ca), len(cb)) // 2):
            continue
        best = max(best, float((x[:n] * y[:n]).sum(axis=1).mean()))
    return max(best, 0.0)


def waveform(a: np.ndarray, b: np.ndarray) -> float:
    n = min(len(a), len(b))
    if n == 0:
        return 0.0
    x, y = a[:n], b[:n]
    denom = float(np.linalg.norm(x) * np.linalg.norm(y))
    return abs(float(np.dot(x, y))) / denom if denom > 1e-9 else 0.0


def key_profile(a: np.ndarray, b: np.ndarray) -> float:
    if len(a) == 0 or len(b) == 0:
        return 0.0
    pa, pb = a.mean(axis=0), b.mean(axis=0)
    denom = float(np.linalg.norm(pa) * np.linalg.norm(pb))
    return float(np.dot(pa, pb)) / denom if denom > 1e-9 else 0.0


def compare_samples(a: np.ndarray, b: np.ndarray, same_bytes: bool = False) -> Comparison:
    ca, cb = chroma(a), chroma(b)
    return Comparison(waveform(a, b), harmony(ca, cb), key_profile(ca, cb), same_bytes)


def compare(one: bytes, two: bytes) -> Comparison:
    """Compares two encoded takes (any format ffmpeg reads)."""
    same = hashlib.sha256(one).digest() == hashlib.sha256(two).digest()
    return compare_samples(decode(one), decode(two), same)
