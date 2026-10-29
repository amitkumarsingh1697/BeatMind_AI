"""CPU stand-in for the music model (US-002), so the whole pipeline runs without a GPU.

It renders a short sine-tone WAV whose pitch is derived from the prompt. It is not music; it only
exercises the queue, storage and status flow end to end.
"""

from __future__ import annotations

import hashlib
import io
import math
import struct
import wave

SAMPLE_RATE = 22_050
NOTES_HZ = (261.63, 293.66, 329.63, 392.00, 440.00)  # C major pentatonic


def render_tone(prompt: str, seconds: float = 2.0) -> bytes:
    """Returns a mono 16-bit WAV. The same prompt always gives the same audio."""
    digest = hashlib.sha256(prompt.encode("utf-8")).digest()
    freq = NOTES_HZ[digest[0] % len(NOTES_HZ)]
    frames = int(SAMPLE_RATE * seconds)
    fade = int(SAMPLE_RATE * 0.05)
    samples = bytearray()
    for i in range(frames):
        envelope = min(1.0, i / fade, (frames - i) / fade)
        value = int(0.3 * 32767 * envelope * math.sin(2 * math.pi * freq * i / SAMPLE_RATE))
        samples += struct.pack("<h", value)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(SAMPLE_RATE)
        out.writeframes(bytes(samples))
    return buf.getvalue()
