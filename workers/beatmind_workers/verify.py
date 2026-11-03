"""Verify a file (US-021, REQ-F-32): look for our watermark and our C2PA content credentials.

The API asks for this when a file's hash matches nothing we stored, for example after it was
re-encoded or trimmed. The answer goes back to the API, which decides what the public may see.
"""

from __future__ import annotations

import logging
from typing import Any

from beatmind_workers import finishing, signing, watermark

log = logging.getLogger("beatmind.verify")


def sniff(data: bytes) -> str | None:
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "wav"
    if data[:3] == b"ID3" or (len(data) > 1 and data[0] == 0xFF and data[1] & 0xE0 == 0xE0):
        return "mp3"
    return None


def credentials(data: bytes, fmt: str | None) -> dict[str, Any]:
    if fmt is None:
        return {"present": False}
    try:
        manifest, state = signing.read_manifest(data, fmt)
    except Exception as e:  # noqa: BLE001 - no manifest, or one the library cannot read
        log.debug("no readable C2PA manifest: %s", e)
        return {"present": False}
    ours = next(
        (a["data"] for a in manifest.get("assertions", []) if a.get("label") == "ai.beatmind.provenance"),
        None,
    )
    actions = next(
        (a for a in manifest.get("assertions", []) if a.get("label", "").startswith("c2pa.actions")), None
    )
    ai = bool(actions) and any(
        x.get("digitalSourceType") == signing.TRAINED_ALGORITHMIC_MEDIA
        for x in actions["data"].get("actions", [])
    )
    out: dict[str, Any] = {"present": True, "state": state, "beatmind": ours is not None, "aiGenerated": ai}
    if ours is not None:
        out["songId"] = ours.get("songId")
        out["take"] = ours.get("take")
    return out


def check(data: bytes) -> dict[str, Any]:
    fmt = sniff(data)
    try:
        detection = watermark.detect(finishing.decode(data))
        mark = {"found": detection.found, "payload": detection.payload, "score": round(detection.score, 2)}
    except Exception as e:  # noqa: BLE001 - not audio ffmpeg can read
        log.debug("could not decode the file: %s", e)
        mark = {"found": False, "decodable": False}
    return {"format": fmt, "watermark": mark, "c2pa": credentials(data, fmt)}
