"""Finishing a take (ARCH-05, TDD section 4 step 5, REQ-F-30): watermark, AI label, C2PA, encode.

Every take is turned into the three files a user can download:

* ``mp3_192``: MP3 at 192 kbps, the free plan's personal-listening file and the in-app preview
* ``mp3_320``: MP3 at 320 kbps for paid plans
* ``wav``: WAV at 48 kHz, 24-bit for paid plans (REQ-F-23)

Each file carries the inaudible watermark (its payload is the take's watermark id), an
"AI-generated" label and "Made with BeatMind AI" in its tags (ID3 for MP3, RIFF INFO for WAV), and
a signed C2PA manifest that says the audio was made by a trained model.
"""

from __future__ import annotations

import hashlib
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from beatmind_workers import watermark
from beatmind_workers.signing import ContentCredentials

FFMPEG = "ffmpeg"
CHANNELS = 2
AI_LABEL = "AI-generated"
ATTRIBUTION = "Made with BeatMind AI"
RIGHTS = {
    "bm_owned": "BeatMind AI. Personal listening only.",
    "user_owned": "Owned by the creator under the BeatMind AI terms.",
}
ENCODINGS = {
    "mp3_192": ("mp3", "audio/mpeg", ["-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3"]),
    "mp3_320": ("mp3", "audio/mpeg", ["-c:a", "libmp3lame", "-b:a", "320k", "-id3v2_version", "3"]),
    "wav": ("wav", "audio/wav", ["-c:a", "pcm_s24le"]),
}


@dataclass
class Asset:
    data: bytes
    content_type: str

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


@dataclass
class FinishedTake:
    watermark_id: int
    seconds: float
    assets: dict[str, Asset] = field(default_factory=dict)


def _ffmpeg(args: list[str], data: bytes) -> bytes:
    proc = subprocess.run(  # noqa: S603 - fixed program, arguments built here
        [FFMPEG, "-hide_banner", "-loglevel", "error", *args], input=data, capture_output=True, check=False
    )
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg failed: {proc.stderr.decode(errors='replace')[-500:]}")
    return proc.stdout


def decode(data: bytes) -> np.ndarray:
    """Any audio file to float32 samples, 48 kHz stereo, shape (n, 2)."""
    raw = _ffmpeg(
        ["-i", "pipe:0", "-f", "f32le", "-ac", str(CHANNELS), "-ar", str(watermark.SAMPLE_RATE), "pipe:1"],
        data,
    )
    return np.frombuffer(raw, dtype="<f4").reshape(-1, CHANNELS)


def tags(payload: dict[str, Any], take_index: int) -> dict[str, str]:
    title = str(payload.get("title") or "BeatMind song")
    return {
        "title": f"{title} (take {take_index})",
        "artist": "BeatMind AI",
        "comment": f"{AI_LABEL}. {ATTRIBUTION}.",
        "copyright": RIGHTS.get(str(payload.get("rights")), RIGHTS["bm_owned"]),
        "AI_GENERATED": "true",
        "BEATMIND_SONG_ID": str(payload.get("songId", "")),
        "BEATMIND_TAKE": str(take_index),
    }


def encode(samples: np.ndarray, name: str, metadata: dict[str, str]) -> bytes:
    container, _, codec = ENCODINGS[name]
    meta: list[str] = []
    for key, value in metadata.items():
        meta += ["-metadata", f"{key}={value}"]
    pcm = np.clip(samples, -1.0, 1.0).astype("<f4").tobytes()
    # A real file, not a pipe: ffmpeg seeks back to write the WAV sizes and the MP3 Xing header.
    with tempfile.TemporaryDirectory(prefix="bm-finish-") as tmp:
        out = Path(tmp) / f"take.{container}"
        _ffmpeg(
            [
                "-f",
                "f32le",
                "-ar",
                str(watermark.SAMPLE_RATE),
                "-ac",
                str(CHANNELS),
                "-i",
                "pipe:0",
                *codec,
                *meta,
                "-f",
                container,
                str(out),
            ],
            pcm,
        )
        return out.read_bytes()


class Finisher:
    def __init__(self, credentials: ContentCredentials | None = None) -> None:
        self.credentials = credentials or ContentCredentials()

    def finish(
        self, audio: bytes, payload: dict[str, Any], take_index: int, formats: tuple[str, ...] | None = None
    ) -> FinishedTake:
        song_id = str(payload.get("songId", ""))
        mark = watermark.payload_for(song_id, take_index)
        samples = watermark.embed(decode(audio), mark)
        metadata = tags(payload, take_index)
        stem = payload.get("stem")
        if stem:
            # US-039: one separated stem of the take, labelled as such.
            metadata["title"] = metadata["title"][:-1] + f", {stem} stem)"
            metadata["BEATMIND_STEM"] = str(stem)
        details = {
            "songId": song_id,
            "take": take_index,
            "watermarkId": mark,
            "label": AI_LABEL,
            "rights": str(payload.get("rights", "bm_owned")),
        }
        if payload.get("version"):
            # US-038: an edited version of the take, and what was done to it.
            details["version"] = int(payload["version"])
            details["edit"] = str(payload.get("op", ""))
        if stem:
            details["stem"] = str(stem)
        finished = FinishedTake(watermark_id=mark, seconds=round(len(samples) / watermark.SAMPLE_RATE, 3))
        for name, (container, content_type, _) in ENCODINGS.items():
            if formats is not None and name not in formats:
                continue
            encoded = encode(samples, name, metadata)
            signed = self.credentials.sign(encoded, container, metadata["title"], details)
            finished.assets[name] = Asset(signed, content_type)
        return finished
