"""ACE-Step 1.5 runner (INT-01): sends a song job to an ACE-Step REST server and fetches the takes.

Run the server on a GPU box with ``uv run acestep-api`` (default port 8001), then start the worker
with ``BEATMIND_RUNNER=acestep`` and ``ACESTEP_URL=http://<gpu-box>:8001``. Setting up that GPU box
is a release task in TODO.md; until then the stub runner stands in.

The request mapping mirrors the benchmark kit in ``bench/`` and the remake spike, so the settings
that sound best there carry over. ``bench/acestep_client.py`` stays standard-library only because it
runs on a bare GPU box; this module is the worker's own copy.
"""

from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

# BeatMind language codes to ACE-Step vocal languages. Hinglish is sung as Hindi with Latin lyrics.
VOCAL_LANGUAGE = {"en": "en", "hi": "hi", "hinglish": "hi"}


@dataclass(frozen=True)
class FetchedTake:
    data: bytes
    format: str


class AceStepError(RuntimeError):
    pass


class AceStepRunner:
    def __init__(self, base_url: str, api_key: str = "", timeout: float = 600, poll_seconds: float = 0.5):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in ("http", "https"):
            raise ValueError(f"ACESTEP_URL must be http(s): {base_url!r}")
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self.poll_seconds = poll_seconds

    @staticmethod
    def params(payload: dict[str, Any]) -> dict[str, Any]:
        """Turns a BeatMind job payload into ACE-Step ``/release_task`` parameters."""
        lyrics = str(payload.get("lyrics") or "")
        if payload.get("instrumental"):
            lyrics = "[Instrumental]"
        return {
            "prompt": str(payload.get("prompt", "")),
            "lyrics": lyrics,
            "vocal_language": VOCAL_LANGUAGE.get(str(payload.get("language", "en")), "en"),
            "audio_duration": float(payload.get("durationSeconds", 60)),
            "batch_size": int(payload.get("takes", 2)),
            # Let the model write lyrics when the user gave none (custom lyrics arrive in Sprint 2).
            "thinking": not lyrics,
            "audio_format": "mp3",
        }

    def render(self, payload: dict[str, Any]) -> list[FetchedTake]:
        started = time.monotonic()
        task_id = self._post("/release_task", self.params(payload))["task_id"]
        while time.monotonic() - started < self.timeout:
            item = self._post("/query_result", {"task_id_list": [task_id]})[0]
            status = item.get("status")
            if status == 1:
                results = json.loads(item["result"]) if isinstance(item["result"], str) else item["result"]
                return [FetchedTake(self._get(r["file"]), "mp3") for r in results]
            if status == 2:
                raise AceStepError(f"ACE-Step task {task_id} failed: {item.get('result')}")
            time.sleep(self.poll_seconds)
        raise AceStepError(f"ACE-Step task {task_id} timed out after {self.timeout:.0f}s")

    def _request(self, url: str, data: bytes | None = None) -> urllib.request.Request:
        if urllib.parse.urlparse(url).scheme not in ("http", "https"):
            raise ValueError(f"unexpected URL scheme: {url}")
        headers = {"Content-Type": "application/json"} if data is not None else {}
        req = urllib.request.Request(url, data=data, headers=headers)  # noqa: S310 - scheme checked
        if self.api_key:
            req.add_header("Authorization", f"Bearer {self.api_key}")
        return req

    def _post(self, path: str, body: dict[str, Any]) -> Any:
        req = self._request(self.base_url + path, json.dumps(body).encode("utf-8"))
        with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310 - scheme checked
            envelope = json.load(resp)
        if envelope.get("code") != 200:
            raise AceStepError(f"{path}: {envelope.get('error')}")
        return envelope["data"]

    def _get(self, file_url: str) -> bytes:
        url = file_url if file_url.startswith("http") else self.base_url + file_url
        with urllib.request.urlopen(self._request(url), timeout=120) as resp:  # noqa: S310 - scheme checked
            return resp.read()
