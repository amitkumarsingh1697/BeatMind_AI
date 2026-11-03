"""Song editor (US-038, REQ-F-10, REQ-F-11): trim, fade, extend and regenerate a section.

Every edit starts from the unwatermarked render of the take's current version, so the finisher
adds exactly one watermark to the result. Trim and fade are plain signal processing. Extend and
regenerate ask the music runner for new audio and join it with equal-power crossfades; the seam
check then compares the jump at each join with the rest of the take.
"""

from __future__ import annotations

import io
import wave
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np

from beatmind_workers import finishing

RATE = finishing.watermark.SAMPLE_RATE
CLICK_GUARD = 0.01  # seconds of fade at a cut so it does not click
CROSSFADE = 0.25  # seconds of overlap at an AI seam
SEAM_WINDOW = 0.05  # seconds either side of a seam that the check looks at
SEAM_LIMIT = (
    3.0  # a seam passes if its largest step is at most this many times the take's typical largest step
)


@dataclass
class Edited:
    samples: np.ndarray
    seams: list[float] = field(default_factory=list)


# Makes `seconds` of new audio for an edit, as float32 (n, 2) at RATE. Arguments: seconds, seed text.
Render = Callable[[float, str], np.ndarray]


def _ramp(n: int) -> np.ndarray:
    return np.linspace(0.0, 1.0, n, dtype=np.float32)[:, None] if n > 0 else np.zeros((0, 1), np.float32)


def trim(samples: np.ndarray, start: float, end: float) -> Edited:
    a, b = int(start * RATE), min(len(samples), int(end * RATE))
    if b - a < RATE:
        raise ValueError("a trimmed take must be at least one second long")
    out = samples[a:b].copy()
    g = int(CLICK_GUARD * RATE)
    out[:g] *= _ramp(g)
    out[-g:] *= _ramp(g)[::-1]
    return Edited(out)


def fade(samples: np.ndarray, fade_in: float, fade_out: float) -> Edited:
    out = samples.copy()
    n_in, n_out = min(len(out), int(fade_in * RATE)), min(len(out), int(fade_out * RATE))
    if n_in:
        out[:n_in] *= _ramp(n_in) ** 2
    if n_out:
        out[-n_out:] *= (_ramp(n_out) ** 2)[::-1]
    return Edited(out)


def _join(left: np.ndarray, right: np.ndarray, overlap: int) -> np.ndarray:
    """Equal-power crossfade of the end of `left` into the start of `right` over `overlap` samples."""
    overlap = min(overlap, len(left), len(right))
    t = np.linspace(0.0, np.pi / 2, overlap, dtype=np.float32)[:, None]
    mixed = left[len(left) - overlap :] * np.cos(t) + right[:overlap] * np.sin(t)
    return np.concatenate([left[: len(left) - overlap], mixed, right[overlap:]]).astype(np.float32)


def _match_level(new: np.ndarray, reference: np.ndarray) -> np.ndarray:
    """Scales new audio to the loudness of the audio next to it, so the seam does not jump in level."""
    ref, cur = float(np.sqrt(np.mean(reference**2)) + 1e-9), float(np.sqrt(np.mean(new**2)) + 1e-9)
    return (new * min(4.0, ref / cur)).astype(np.float32)


def extend(samples: np.ndarray, seconds: float, render: Render, seed: str) -> Edited:
    overlap = int(CROSSFADE * RATE)
    new = render(seconds + CROSSFADE, seed)
    new = _match_level(new, samples[-RATE * 2 :])
    out = _join(samples, new, overlap)
    return Edited(out, [(len(samples) - overlap / 2) / RATE])


def regenerate(samples: np.ndarray, start: float, end: float, render: Render, seed: str) -> Edited:
    a, b = int(start * RATE), min(len(samples), int(end * RATE))
    overlap = int(CROSSFADE * RATE)
    new = render((b - a) / RATE + CROSSFADE, seed)
    new = _match_level(new, samples[max(0, a - RATE) : min(len(samples), b + RATE)])
    head = samples[: a + overlap // 2]
    tail = samples[max(0, b - overlap // 2) :]
    left = _join(head, new, overlap)
    out = _join(left, tail, overlap)
    return Edited(out, [(len(head) - overlap / 2) / RATE, (len(left) - overlap / 2) / RATE])


def seam_check(samples: np.ndarray, seams: list[float]) -> dict:
    """Largest sample-to-sample step near each seam, against the 99th percentile of steps in 50 ms
    blocks across the take. A click or a level jump at a seam shows as a ratio well above 1."""
    mono = samples.mean(axis=1)
    steps = np.abs(np.diff(mono))
    block = int(SEAM_WINDOW * RATE)
    usable = len(steps) // block * block
    typical = float(np.percentile(steps[:usable].reshape(-1, block).max(axis=1), 99)) if usable else 0.0
    typical = max(typical, 1e-6)
    results = []
    for s in seams:
        c = int(s * RATE)
        lo, hi = max(0, c - block), min(len(steps), c + block)
        peak = float(steps[lo:hi].max()) if hi > lo else 0.0
        results.append({"atSeconds": round(s, 3), "ratio": round(peak / typical, 3)})
    worst = max((r["ratio"] for r in results), default=0.0)
    return {"seams": results, "worstRatio": worst, "limit": SEAM_LIMIT, "passed": worst <= SEAM_LIMIT}


def apply(samples: np.ndarray, op: str, params: dict, render: Render, seed: str) -> Edited:
    if op == "trim":
        return trim(samples, float(params["start"]), float(params["end"]))
    if op == "fade":
        return fade(samples, float(params.get("fadeIn", 0)), float(params.get("fadeOut", 0)))
    if op == "extend":
        return extend(samples, float(params["seconds"]), render, seed)
    if op == "regenerate":
        return regenerate(samples, float(params["start"]), float(params["end"]), render, seed)
    raise ValueError(f"unknown edit {op!r}")


def to_wav(samples: np.ndarray) -> bytes:
    """16-bit stereo WAV at the finishing rate: the raw render the next edit starts from."""
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as out:
        out.setnchannels(samples.shape[1])
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(pcm.tobytes())
    return buf.getvalue()


def stub_render(seconds: float, seed: str) -> np.ndarray:
    """Stand-in for the model: a soft chord whose notes come from the seed, with no edges."""
    from beatmind_workers import stub

    tone = finishing.decode(stub.render_tone(seed, seconds))
    n = int(seconds * RATE)
    if len(tone) < n:
        tone = np.pad(tone, ((0, n - len(tone)), (0, 0)))
    return tone[:n].astype(np.float32)
