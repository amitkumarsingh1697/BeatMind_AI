"""Paid fallback runner (US-016, INT-02, ADR-007): Google's Lyria music model on Vertex AI.

Used only for jobs the API routed to provider "lyria" (paid plans, long GPU queue or a routed
language, under the daily spend cap). Run a separate worker with ``BEATMIND_PROVIDERS=lyria`` and
``BEATMIND_RUNNER=lyria``. The request below is Vertex AI's ``predict`` call for ``lyria-002``,
which returns instrumental 30-second WAV clips; checking the Lyria 3 Pro model name, length and
vocals support is a release task in TODO.md.
"""

from __future__ import annotations

import base64
import json
import os
import urllib.parse
import urllib.request
from typing import Any

from beatmind_workers import beat
from beatmind_workers.acestep import FetchedTake


class LyriaRunner:
    def __init__(self, endpoint: str, token: str, usd_per_take: float, timeout: float = 300) -> None:
        if urllib.parse.urlparse(endpoint).scheme != "https":
            raise ValueError("LYRIA_ENDPOINT must be https")
        self.endpoint = endpoint
        self.token = token
        self.usd_per_take = usd_per_take
        self.timeout = timeout

    @classmethod
    def from_env(cls) -> LyriaRunner:
        return cls(
            os.environ["LYRIA_ENDPOINT"],
            os.environ.get("LYRIA_TOKEN", ""),
            float(os.environ.get("BEATMIND_FALLBACK_USD_PER_TAKE", "0.04")),
        )

    @staticmethod
    def body(payload: dict[str, Any]) -> dict[str, Any]:
        prompt = str(payload.get("prompt", ""))
        if payload.get("beat"):
            prompt = beat.style(payload["beat"], prompt)
        return {
            "instances": [{"prompt": prompt, "negative_prompt": "vocals imitating a real singer"}],
            "parameters": {"sample_count": int(payload.get("takes", 2))},
        }

    def render(self, payload: dict[str, Any]) -> list[FetchedTake]:
        req = urllib.request.Request(  # noqa: S310 - https checked in __init__
            self.endpoint,
            data=json.dumps(self.body(payload)).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.token}"},
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:  # noqa: S310 - https checked
            predictions = json.load(resp).get("predictions", [])
        return [FetchedTake(base64.b64decode(p["bytesBase64Encoded"]), "wav") for p in predictions]

    def cost(self, takes: int) -> float:
        return round(self.usd_per_take * takes, 4)
