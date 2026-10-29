"""Minimal client for the ACE-Step 1.5 REST server (`uv run acestep-api`, default port 8001).

Standard library only, so it runs on a freshly rented GPU box with no extra installs.
API reference: https://github.com/ace-step/ACE-Step-1.5/blob/main/docs/en/API.md
"""

from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any


@dataclass
class TaskResult:
    task_id: str
    ok: bool
    seconds: float
    files: list[str] = field(default_factory=list)
    dit_model: str = ""
    lm_model: str = ""
    error: str = ""


class AceStepClient:
    def __init__(self, base_url: str = "http://localhost:8001", api_key: str = "", timeout: float = 600):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def _post(self, path: str, body: dict[str, Any]) -> Any:
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(  # noqa: S310 - URL comes from the operator's own flag
            self.base_url + path, data=data, headers={"Content-Type": "application/json"}
        )
        if self.api_key:
            req.add_header("Authorization", f"Bearer {self.api_key}")
        with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310
            envelope = json.load(resp)
        if envelope.get("code") != 200:
            raise RuntimeError(f"{path}: {envelope.get('error')}")
        return envelope["data"]

    def generate(self, params: dict[str, Any], poll_seconds: float = 0.5) -> TaskResult:
        """Submits one task and waits for it. Time is measured from submit to result."""
        started = time.monotonic()
        task_id = self._post("/release_task", params)["task_id"]
        while time.monotonic() - started < self.timeout:
            item = self._post("/query_result", {"task_id_list": [task_id]})[0]
            status = item.get("status")
            if status == 1:
                results = json.loads(item["result"]) if isinstance(item["result"], str) else item["result"]
                return TaskResult(
                    task_id=task_id,
                    ok=True,
                    seconds=time.monotonic() - started,
                    files=[r["file"] for r in results],
                    dit_model=results[0].get("dit_model", ""),
                    lm_model=results[0].get("lm_model", ""),
                )
            if status == 2:
                return TaskResult(task_id, False, time.monotonic() - started, error=str(item.get("result")))
            time.sleep(poll_seconds)
        return TaskResult(task_id, False, time.monotonic() - started, error="timed out")

    def download(self, file_url: str, dest: str) -> None:
        url = file_url if file_url.startswith("http") else self.base_url + file_url
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme not in ("http", "https"):
            raise ValueError(f"unexpected URL scheme: {url}")
        req = urllib.request.Request(url)  # noqa: S310
        if self.api_key:
            req.add_header("Authorization", f"Bearer {self.api_key}")
        with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:  # noqa: S310
            out.write(resp.read())
