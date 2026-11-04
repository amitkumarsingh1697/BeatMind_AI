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

from beatmind_workers import beat

# BeatMind language codes to ACE-Step vocal languages. Hinglish is sung as Hindi with Latin lyrics.
VOCAL_LANGUAGE = {"en": "en", "hi": "hi", "hinglish": "hi"}
# Vocal type (US-017) as words in the ACE-Step caption.
VOCAL_STYLE = {"female": "female vocals", "male": "male vocals", "duet": "male and female duet vocals"}


SUNG_IN = {"en": "sung in English", "hi": "sung in Hindi", "hinglish": "sung in Hindi (Hinglish)"}


def sample_query(prompt: str, language: str, seconds: float) -> str:
    """The description ACE-Step's sample mode writes a song from: the vibe, the vocal type already
    in ``prompt``, the language, and the length asked for (the model picks the final length)."""
    if seconds >= 90:
        length = f"about {round(seconds / 60, 1):g} minutes long"
    else:
        length = f"about {round(seconds)} seconds long"
    parts = [prompt.strip() or "a song", SUNG_IN.get(language, "sung in English"), length]
    return ", ".join(parts)


def written_lyrics(result: dict[str, Any]) -> str:
    """The lyrics ACE-Step sang, as its result reports them; empty for an instrumental."""
    text = str(result.get("lyrics") or "").strip()
    return "" if text.lower() in ("", "[inst]", "[instrumental]") else text


@dataclass(frozen=True)
class FetchedTake:
    data: bytes
    format: str
    # Lyrics the model wrote itself (vibe mode through sample mode); empty when none.
    lyrics: str = ""


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
        settings = payload.get("beat")
        if payload.get("instrumental") or settings:
            lyrics = "[Instrumental]"
        prompt = str(payload.get("prompt", ""))
        if settings:
            # US-015: beat settings become style words; BPM and key also go as model parameters.
            prompt = beat.style(settings, prompt)
        vocal = payload.get("vocalType")
        if not payload.get("instrumental") and vocal in VOCAL_STYLE:
            prompt = f"{prompt}, {VOCAL_STYLE[vocal]}" if prompt else VOCAL_STYLE[vocal]
        language = str(payload.get("language", "en"))
        duration = float(payload.get("durationSeconds", 60))
        params: dict[str, Any] = {
            "prompt": prompt,
            "lyrics": lyrics,
            "vocal_language": VOCAL_LANGUAGE.get(language, "en"),
            "audio_duration": duration,
            "batch_size": int(payload.get("takes", 2)),
            # Vibe mode: let the 5Hz LM plan the song; custom lyrics are sung as given.
            "thinking": not lyrics,
            "audio_format": "mp3",
        }
        if not lyrics:
            # ACE-Step sings nothing when the lyrics are empty, and "thinking" does not write any.
            # Sample mode has its LM write the caption and lyrics from the description first.
            params["sample_mode"] = True
            params["sample_query"] = sample_query(prompt, language, duration)
        if settings:
            params["bpm"] = int(settings["bpm"])
            if settings.get("key") not in (None, "any"):
                params["key_scale"] = settings["key"]
        return params

    def render(self, payload: dict[str, Any]) -> list[FetchedTake]:
        started = time.monotonic()
        task_id = self._post("/release_task", self.params(payload))["task_id"]
        while time.monotonic() - started < self.timeout:
            item = self._post("/query_result", {"task_id_list": [task_id]})[0]
            status = item.get("status")
            if status == 1:
                results = json.loads(item["result"]) if isinstance(item["result"], str) else item["result"]
                if not results or any(not r.get("file") for r in results):
                    # ACE-Step reports success but saved nothing when it cannot write the file,
                    # for example when ffmpeg is missing for MP3 export.
                    raise AceStepError(
                        f"ACE-Step task {task_id} finished without an audio file; check the ACE-Step "
                        "server log (is ffmpeg installed on that machine?)"
                    )
                return [FetchedTake(self._get(r["file"]), "mp3", written_lyrics(r)) for r in results]
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
