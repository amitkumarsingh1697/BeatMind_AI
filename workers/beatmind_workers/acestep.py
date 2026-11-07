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
import secrets
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any

from beatmind_workers import beat, intent

# BeatMind language codes to ACE-Step vocal languages. Hinglish is sung as Hindi with Latin lyrics.
VOCAL_LANGUAGE = {"en": "en", "hi": "hi", "hinglish": "hi"}
# Vocal type (US-017) as words in the ACE-Step caption.
VOCAL_STYLE = {"female": "female vocals", "male": "male vocals", "duet": "male and female duet vocals"}


SUNG_IN = {"en": "sung in English", "hi": "sung in Hindi", "hinglish": "sung in Hindi (Hinglish)"}


def sample_query(prompt: str, language: str, seconds: float) -> str:
    """The description ACE-Step's LM writes a song plan from: the vibe, the vocal type already in
    ``prompt``, the language, and the length asked for. The music model still gets the length we
    send, not the LM's."""
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
    # Lyrics the model wrote itself (vibe mode); empty when none.
    lyrics: str = ""
    # What was asked of ACE-Step and what it reported for this take (seed, plan, models), so the
    # song can be audited later. Empty for runners that do not report it.
    meta: dict[str, Any] = field(default_factory=dict)


class AceStepError(RuntimeError):
    pass


# A plan that does not fit the request is rewritten up to this many times before the job fails.
MAX_PLAN_ATTEMPTS = 4
LM_TEMPERATURE = 0.85
# Writing a plan took 16 to 21 s on an M-series Mac; the first call also loads the LM.
PLAN_TIMEOUT = 300


def _seconds(value: Any) -> float | None:
    try:
        return float(value) if value not in (None, "", "N/A") else None
    except (TypeError, ValueError):
        return None


def is_vibe(payload: dict[str, Any]) -> bool:
    """Vibe mode with vocals: the model writes the words."""
    return not (payload.get("lyrics") or payload.get("instrumental") or payload.get("beat"))


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
    def caption(payload: dict[str, Any]) -> str:
        """The creator's own description, with beat settings and the vocal type as style words."""
        prompt = str(payload.get("prompt", ""))
        settings = payload.get("beat")
        if settings:
            # US-015: beat settings become style words; BPM and key also go as model parameters.
            prompt = beat.style(settings, prompt)
        vocal = payload.get("vocalType")
        if not payload.get("instrumental") and vocal in VOCAL_STYLE:
            prompt = f"{prompt}, {VOCAL_STYLE[vocal]}" if prompt else VOCAL_STYLE[vocal]
        return prompt

    @staticmethod
    def params(
        payload: dict[str, Any], plan: dict[str, Any] | None = None, seed: int | None = None
    ) -> dict[str, Any]:
        """``/release_task`` parameters for one take.

        The music model gets the creator's description word for word, the length and language they
        chose, and one seed. ACE-Step's own rewriting is off (``use_cot_caption`` and
        ``use_cot_language``), and sample mode is not used: in the RCA both replaced the request with
        the LM's reading of it. In vibe mode ``plan`` carries the lyrics, BPM, key and time signature
        the LM wrote for this take (see ``plan``).
        """
        lyrics = str(payload.get("lyrics") or "")
        settings = payload.get("beat")
        if payload.get("instrumental") or settings:
            lyrics = "[Instrumental]"
        language = str(payload.get("language", "en"))
        written = bool(plan and plan.get("lyrics"))
        if written:
            lyrics = str(plan["lyrics"])  # type: ignore[index]
        params: dict[str, Any] = {
            "prompt": AceStepRunner.caption(payload),
            "lyrics": lyrics,
            "vocal_language": VOCAL_LANGUAGE.get(language, "en"),
            "audio_duration": float(payload.get("durationSeconds", 60)),
            # One take per request: a batch shares one LM plan, so both takes were the same song.
            "batch_size": 1,
            # The 5Hz LM turns the lyrics into audio codes (structure and melody) for each take.
            # Typed lyrics are sung as given, as before.
            "thinking": written or not lyrics,
            "use_cot_caption": False,
            "use_cot_language": False,
            "audio_format": "mp3",
        }
        if seed is not None:
            params["use_random_seed"] = False
            params["seed"] = int(seed)
        if settings:
            params["bpm"] = int(settings["bpm"])
            if settings.get("key") not in (None, "any"):
                params["key_scale"] = settings["key"]
        elif plan:
            # Each take keeps the tempo, key and metre of its own plan, so the two takes differ.
            if plan.get("bpm"):
                params["bpm"] = int(plan["bpm"])
            if plan.get("keyscale"):
                params["key_scale"] = str(plan["keyscale"])
            if plan.get("timesignature"):
                params["time_signature"] = str(plan["timesignature"])
        return params

    def plan(self, payload: dict[str, Any], earlier: list[str]) -> dict[str, Any]:
        """Has ACE-Step's LM write lyrics and musical metadata for one take, and checks them.

        A plan that does not fit the request (see ``intent.problems``), or repeats an earlier take's
        lyrics, is written again; after ``MAX_PLAN_ATTEMPTS`` the job fails rather than sing it.
        """
        language = str(payload.get("language", "en"))
        duration = float(payload.get("durationSeconds", 60))
        prompt = self.caption(payload)
        query = sample_query(prompt, language, duration)
        rejected: list[dict[str, Any]] = []
        for attempt in range(1, MAX_PLAN_ATTEMPTS + 1):
            data = self._post(
                "/v1/create_sample",
                {
                    "query": query,
                    "instrumental": False,
                    "vocal_language": VOCAL_LANGUAGE.get(language, "en"),
                    "temperature": LM_TEMPERATURE,
                },
                timeout=PLAN_TIMEOUT,
            )
            lyrics = intent.clean(written_lyrics(data))
            caption = str(data.get("caption") or "")
            # Checked as it will be sung: cut to the take's length first.
            fitted = intent.fit(lyrics, _seconds(data.get("duration")), duration)
            problems = intent.problems(fitted, caption, prompt, language, earlier)
            if not problems:
                return {
                    "lyrics": fitted,
                    "lyricsLinesWritten": len(lyrics.splitlines()),
                    "lyricsLinesSung": len(fitted.splitlines()),
                    "caption": caption,
                    "bpm": data.get("bpm"),
                    "keyscale": data.get("keyscale") or "",
                    "timesignature": data.get("timesignature") or "",
                    "lmDuration": data.get("duration"),
                    "vocalLanguage": data.get("vocal_language") or "",
                    "query": query,
                    "attempts": attempt,
                    "rejected": rejected,
                }
            rejected.append({"caption": caption[:300], "problems": problems})
        raise AceStepError(
            f"ACE-Step wrote no song plan that fits the request in {MAX_PLAN_ATTEMPTS} tries; last: "
            + "; ".join(rejected[-1]["problems"])
        )

    def render(self, payload: dict[str, Any]) -> list[FetchedTake]:
        """Every take is its own composition: its own plan (vibe mode), its own seed, one request."""
        takes = int(payload.get("takes", 2))
        seeds: list[int] = []
        out: list[FetchedTake] = []
        for _ in range(takes):
            out.append(self.render_one(payload, [t.lyrics for t in out if t.lyrics], seeds))
        return out

    def render_one(self, payload: dict[str, Any], earlier: list[str], seeds: list[int]) -> FetchedTake:
        """One more take, different from the ``earlier`` lyrics and ``seeds`` (both are appended to)."""
        plan = self.plan(payload, earlier) if is_vibe(payload) else None
        seed = secrets.randbelow(2**31 - 1) + 1
        while seed in seeds:
            seed = secrets.randbelow(2**31 - 1) + 1
        seeds.append(seed)
        params = self.params(payload, plan, seed)
        r = self._run(params)
        meta: dict[str, Any] = {
            "seed": seed,
            "captionSent": params["prompt"],
            "durationRequested": params["audio_duration"],
            "bpm": params.get("bpm"),
            "keyScale": params.get("key_scale", ""),
            "timeSignature": params.get("time_signature", ""),
            "seedReported": r.get("seed_value"),
            "ditModel": r.get("dit_model") or "",
            "metas": r.get("metas") if isinstance(r.get("metas"), dict) else {},
        }
        if plan:
            meta["plan"] = {k: v for k, v in plan.items() if k != "lyrics"}
        lyrics = str(plan["lyrics"]) if plan else ""
        return FetchedTake(self._get(r["file"]), "mp3", lyrics, meta)

    def _run(self, params: dict[str, Any]) -> dict[str, Any]:
        """Sends one ``/release_task`` and waits for its single result."""
        started = time.monotonic()
        task_id = self._post("/release_task", params)["task_id"]
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
                return results[0]
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

    def _post(self, path: str, body: dict[str, Any], timeout: float = 60) -> Any:
        req = self._request(self.base_url + path, json.dumps(body).encode("utf-8"))
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 - scheme checked
            envelope = json.load(resp)
        if envelope.get("code") != 200:
            raise AceStepError(f"{path}: {envelope.get('error')}")
        return envelope["data"]

    def _get(self, file_url: str) -> bytes:
        url = file_url if file_url.startswith("http") else self.base_url + file_url
        with urllib.request.urlopen(self._request(url), timeout=120) as resp:  # noqa: S310 - scheme checked
            return resp.read()
