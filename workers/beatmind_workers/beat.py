"""Beat mode (US-015, REQ-F-04): style text for the model, a stub beat and the tempo check.

Beat mode takes a genre, a BPM from 60 to 200, a key and instruments to include or exclude, and
always makes an instrumental. After rendering, every take goes through a tempo check: the onset
strength envelope is autocorrelated and the strongest lag in the 60 to 200 BPM range is the
measured tempo. A take counts as on tempo when it is within 4% of the target. Models often land
on half or double time, which sounds right to a listener, so that is reported as ``octave``
and still counts.

The result goes in the take as ``tempo``; the API stores it and the ledger records it.
"""

from __future__ import annotations

import hashlib
import io
import math
import struct
import wave
from typing import Any

import numpy as np

MIN_BPM = 60
MAX_BPM = 200
TOLERANCE = 0.04
SAMPLE_RATE = 22_050

# Instruments as the model reads them.
INSTRUMENT_WORDS = {
    "808": "808 bass",
    "keys": "electric keys",
    "pads": "synth pads",
}


def _word(name: str) -> str:
    return INSTRUMENT_WORDS.get(name, name.replace("_", " "))


def style(beat: dict[str, Any], extra: str = "") -> str:
    """The style text for the model, e.g. ``lofi, 90 BPM, A minor, featuring piano and tabla, no drums``."""
    parts = [str(beat.get("genre", "")).replace("_", " "), f"{int(beat['bpm'])} BPM"]
    key = beat.get("key")
    if key and key != "any":
        parts.append(str(key))
    include = [_word(i) for i in beat.get("include") or []]
    if include:
        parts.append("featuring " + ", ".join(include))
    for name in beat.get("exclude") or []:
        parts.append(f"no {_word(name)}")
    parts.append("instrumental, no vocals")
    if extra:
        parts.append(extra)
    return ", ".join(p for p in parts if p)


def render_stub(bpm: float, seed: str, seconds: float = 4.0) -> bytes:
    """A CPU stand-in for a beat: a decaying low thump on every beat.

    Not music; it gives the tempo check something real to measure in local runs and tests.
    """
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    pitch = 55.0 + digest[0] % 30
    frames = int(SAMPLE_RATE * seconds)
    samples = np.zeros(frames, dtype=np.float64)
    period = 60.0 / bpm
    hit = int(SAMPLE_RATE * 0.12)
    t = np.arange(hit) / SAMPLE_RATE
    kick = np.sin(2 * math.pi * pitch * t) * np.exp(-t * 30)
    beat = 0
    while True:
        start = int(beat * period * SAMPLE_RATE)
        if start >= frames:
            break
        end = min(frames, start + hit)
        samples[start:end] += kick[: end - start]
        beat += 1
    samples = np.clip(samples * 0.6, -1, 1)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(SAMPLE_RATE)
        out.writeframes(b"".join(struct.pack("<h", int(v * 32767)) for v in samples))
    return buf.getvalue()


def read_wav(data: bytes) -> tuple[np.ndarray, int]:
    """Mono float samples from a 16-bit PCM WAV."""
    with wave.open(io.BytesIO(data)) as w:
        rate = w.getframerate()
        channels = w.getnchannels()
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768
    if channels > 1:
        raw = raw.reshape(-1, channels).mean(axis=1)
    return raw, rate


def measure_tempo(samples: np.ndarray, rate: int) -> float | None:
    """Estimated tempo in BPM, or None if the audio is too short or has no clear pulse."""
    if samples.ndim > 1:
        samples = samples.mean(axis=1)
    hop = 256
    frame = 1024
    if len(samples) < rate * 2:
        return None
    n = 1 + (len(samples) - frame) // hop
    window = np.hanning(frame)
    energy = np.empty(n)
    for i in range(n):
        chunk = samples[i * hop : i * hop + frame] * window
        energy[i] = math.log1p(float(np.sum(chunk * chunk)) * 100)
    # Onset strength: only rises in energy.
    onset = np.maximum(0, np.diff(energy))
    if not onset.any():
        return None
    onset = onset - onset.mean()
    fps = rate / hop
    ac = np.correlate(onset, onset, mode="full")[len(onset) - 1 :]
    # Search a little past each end so a tempo right at 60 or 200 BPM still has its peak inside.
    lo = max(1, int(fps * 60 / (MAX_BPM * 1.05)))
    hi = min(len(ac) - 2, int(fps * 60 / (MIN_BPM * 0.95)) + 2)
    if hi <= lo + 1:
        return None
    # The biased autocorrelation falls with lag, so the shortest strong period wins; a model that
    # lands on half or double time is caught by the octave rule in check().
    lag = lo + int(np.argmax(ac[lo:hi]))
    # Parabolic interpolation around the peak for sub-frame precision.
    if 0 < lag < len(ac) - 1:
        a, b, c = ac[lag - 1], ac[lag], ac[lag + 1]
        denom = a - 2 * b + c
        shift = 0.5 * (a - c) / denom if denom != 0 else 0.0
    else:
        shift = 0.0
    return 60 * fps / (lag + shift)


def check(samples: np.ndarray, rate: int, target_bpm: float) -> dict[str, Any]:
    """The tempo check result stored with the take."""
    measured = measure_tempo(samples, rate)
    result: dict[str, Any] = {"targetBpm": int(target_bpm), "toleranceBpm": round(target_bpm * TOLERANCE, 1)}
    if measured is None:
        result.update({"measuredBpm": None, "withinTolerance": False, "octave": False})
        return result
    ok = bool(abs(measured - target_bpm) <= target_bpm * TOLERANCE)
    octave = False
    if not ok:
        for factor in (2.0, 0.5):
            if abs(measured * factor - target_bpm) <= target_bpm * TOLERANCE:
                ok, octave = True, True
                break
    result.update({"measuredBpm": round(float(measured), 1), "withinTolerance": bool(ok), "octave": octave})
    return result
