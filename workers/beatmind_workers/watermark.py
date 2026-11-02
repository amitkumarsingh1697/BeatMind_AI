"""Development audio watermark (TDD 6.2, REQ-F-30).

A spread-spectrum mark that carries a 32-bit payload linked to the take (its watermark id). It
survives MP3 encoding at 128 kbps and above and is detected with the key alone; the original file
is not needed. It stands in for AudioSeal (INT-06), which needs PyTorch and runs on the GPU box;
swapping it in is a release task in TODO.md. This one is not robust to cropping the start, pitch
shifts or time stretching, so it is for development and tests only.

How it works: two pseudo-random +1/-1 sequences from a secret key are added at about -40 dB below
the music. One (the pilot) finds the alignment; the other is flipped per bit to carry the payload.
Detection whitens the audio, finds the pilot peak, then reads each bit by correlation.
"""

from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass

import numpy as np

SAMPLE_RATE = 48_000
BITS = 32
CHIP = 1_500  # samples per bit; one payload cycle is 48,000 samples (1 s)
PERIOD = 10 * SAMPLE_RATE  # the sequences repeat every 10 s; detection reads the first 10 s
MAX_LAG = 3_000  # encoder delay and padding, in samples
DETECT_THRESHOLD = 8.0  # pilot peak in standard deviations; unmarked audio scores about 2


@dataclass(frozen=True)
class Detection:
    found: bool
    payload: int | None
    score: float
    lag: int


def _key() -> bytes:
    return os.environ.get("BEATMIND_WATERMARK_KEY", "beatmind-dev-watermark").encode()


def _sequence(name: bytes) -> np.ndarray:
    seed = int.from_bytes(hashlib.sha256(_key() + b":" + name).digest()[:8], "little")
    rng = np.random.default_rng(seed)
    return rng.integers(0, 2, size=PERIOD).astype(np.float32) * 2 - 1


def payload_for(song_id: str, take_index: int) -> int:
    """The 32-bit watermark id for a take, derived from the song and take."""
    return int.from_bytes(hashlib.sha256(f"{song_id}:{take_index}".encode()).digest()[:4], "big")


def _tiled(seq: np.ndarray, n: int) -> np.ndarray:
    reps = -(-n // PERIOD)
    return np.tile(seq, reps)[:n]


def embed(samples: np.ndarray, payload: int) -> np.ndarray:
    """Returns a marked copy of ``samples`` (float32, shape (n,) or (n, channels), at 48 kHz)."""
    x = np.asarray(samples, dtype=np.float32)
    n = x.shape[0]
    bits = np.array([((payload >> i) & 1) * 2 - 1 for i in range(BITS)], dtype=np.float32)
    symbols = bits[(np.arange(n) // CHIP) % BITS]
    mark = 0.7 * _tiled(_sequence(b"pilot"), n) + symbols * _tiled(_sequence(b"data"), n)
    rms = float(np.sqrt(np.mean(np.square(x)))) if x.size else 0.0
    strength = float(np.clip(0.01 * rms, 0.0003, 0.003)) / 1.7
    mark *= strength
    if x.ndim == 2:
        mark = mark[:, None]
    return np.clip(x + mark, -1.0, 1.0).astype(np.float32)


def detect(samples: np.ndarray) -> Detection:
    """Looks for the mark in the first 10 s of ``samples`` (48 kHz, any number of channels)."""
    x = np.asarray(samples, dtype=np.float64)
    if x.ndim == 2:
        x = x.mean(axis=1)
    n = PERIOD
    x = np.pad(x[:n], (0, max(0, n - len(x))))
    size = 2 * n
    spectrum = np.fft.rfft(x, size)
    white = spectrum / (np.abs(spectrum) + 1e-9)
    pilot = _sequence(b"pilot").astype(np.float64)
    corr = np.fft.irfft(white * np.conj(np.fft.rfft(pilot, size)), size)
    window = np.concatenate([corr[-MAX_LAG:], corr[: MAX_LAG + 1]])
    best = int(np.argmax(window))
    lag = best - MAX_LAG
    score = float(window[best] / (np.std(window) + 1e-12))
    if score < DETECT_THRESHOLD:
        return Detection(False, None, score, lag)
    aligned = np.roll(np.fft.irfft(white, size)[:n], -lag)
    product = aligned * _sequence(b"data")
    sums = np.zeros(BITS)
    np.add.at(sums, (np.arange(n) // CHIP) % BITS, product)
    payload = sum(1 << i for i in range(BITS) if sums[i] > 0)
    return Detection(True, payload, score, lag)
