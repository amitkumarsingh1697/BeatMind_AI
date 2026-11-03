"""Worker process: poll the queue, run the model, store audio, record the result."""

from __future__ import annotations

import logging
import os
import signal
import socket
import time

import psycopg

from beatmind_workers import beat, editing, finishing, lyrics, queue, stems, stub, verify
from beatmind_workers.acestep import AceStepRunner, FetchedTake
from beatmind_workers.finishing import Finisher
from beatmind_workers.lyria import LyriaRunner
from beatmind_workers.lyrics import LlmLyricWriter, StubLyricWriter
from beatmind_workers.similarity import Screen
from beatmind_workers.storage import ObjectStore

log = logging.getLogger("beatmind.worker")

TAKES_PER_REQUEST = 2  # REQ-F-05
CONTENT_TYPES = {"wav": "audio/wav", "mp3": "audio/mpeg"}


def render(
    job: queue.Job, runner: str, acestep: AceStepRunner | None, lyria: LyriaRunner | None = None
) -> list[FetchedTake]:
    """Makes the takes for a job with the configured runner."""
    takes = int(job.payload.get("takes", TAKES_PER_REQUEST))
    if job.payload.get("provider") == "lyria" or runner == "lyria":
        if lyria is None:
            raise ValueError("job is for the Lyria fallback but this worker has no LYRIA_ENDPOINT")
        return lyria.render(job.payload)
    if runner == "stub":
        prompt = str(job.payload.get("prompt", ""))
        settings = job.payload.get("beat")
        if settings:
            bpm = float(settings["bpm"])
            return [
                FetchedTake(beat.render_stub(bpm, f"{prompt}#take{i}"), "wav") for i in range(1, takes + 1)
            ]
        return [FetchedTake(stub.render_tone(f"{prompt}#take{i}"), "wav") for i in range(1, takes + 1)]
    if runner == "acestep" and acestep is not None:
        return acestep.render(job.payload)
    raise ValueError(f"unknown runner {runner!r}; use stub or acestep")


def tempo_check(take: FetchedTake, target_bpm: float) -> dict:
    """US-015: measures the tempo of a rendered beat."""
    if take.format == "wav":
        try:
            samples, rate = beat.read_wav(take.data)
            return beat.check(samples, rate, target_bpm)
        except Exception as e:  # noqa: BLE001 - not 16-bit PCM; fall back to ffmpeg
            log.debug("reading the WAV directly failed (%s); decoding with ffmpeg", e)
    samples = finishing.decode(take.data)
    return beat.check(samples.mean(axis=1), finishing.watermark.SAMPLE_RATE, target_bpm)


def edit_renderer(job: queue.Job, runner: str, acestep: AceStepRunner | None) -> editing.Render:
    """New audio for extend and regenerate, from the runner that made the song."""
    if runner != "acestep" or acestep is None:
        return editing.stub_render

    def make(seconds: float, seed: str) -> object:
        payload = dict(job.payload, durationSeconds=max(10.0, seconds), takes=1)
        payload["prompt"] = str(job.payload.get("editPrompt") or job.payload.get("prompt", ""))
        clip = finishing.decode(acestep.render(payload)[0].data)
        return clip[: int(seconds * editing.RATE)]

    return make  # type: ignore[return-value]


def run_edit(
    job: queue.Job, store: ObjectStore, runner: str, acestep: AceStepRunner | None, finisher: Finisher
) -> dict:
    """US-038: one edit of one take, finished like a new take (watermark, labels, C2PA)."""
    p = job.payload
    index, version, op = int(p["takeIndex"]), int(p["version"]), str(p["op"])
    source = finishing.decode(store.get(str(p["sourceKey"])))
    seed = f"{p.get('songId')}#take{index}#v{version}"
    edited = editing.apply(source, op, dict(p.get("params", {})), edit_renderer(job, runner, acestep), seed)
    raw = editing.to_wav(edited.samples)
    base = f"edits/{p.get('songId')}/take-{index}-v{version}"
    raw_key = store.put(f"{base}-raw.wav", raw, CONTENT_TYPES["wav"])
    finished = finisher.finish(raw, p, index)
    ext = {"mp3_192": "192.mp3", "mp3_320": "320.mp3", "wav": "master.wav"}
    entry: dict = {
        "runner": runner if op in ("extend", "regenerate") else "editor",
        "rawKey": raw_key,
        "assets": {
            name: {
                "key": store.put(f"{base}-{ext[name]}", asset.data, asset.content_type),
                "contentType": asset.content_type,
                "sha256": asset.sha256,
                "bytes": len(asset.data),
            }
            for name, asset in finished.assets.items()
        },
        "watermarkId": finished.watermark_id,
        "durationSeconds": finished.seconds,
        "c2pa": True,
    }
    if edited.seams:
        entry["seam"] = editing.seam_check(edited.samples, edited.seams)
    return {"takes": [entry]}


def run_stems(
    job: queue.Job, store: ObjectStore, finisher: Finisher, separator: stems.Separator, model: str
) -> dict:
    """US-039: four stems of one version of a take, from its unwatermarked render. The sum test runs
    on the raw stems; each stem is then finished as a WAV with its own label, mark and C2PA."""
    p = job.payload
    index, version = int(p["takeIndex"]), int(p["version"])
    mix = finishing.decode(store.get(str(p["sourceKey"])))
    separated = separator(mix)
    check = stems.sum_check(mix, separated)
    base = f"stems/{p.get('songId')}/take-{index}-v{version}"
    assets = {}
    for name in stems.NAMES:
        finished = finisher.finish(
            editing.to_wav(separated[name]), {**p, "stem": name}, index, formats=("wav",)
        )
        asset = finished.assets["wav"]
        assets[name] = {
            "key": store.put(f"{base}-{name}.wav", asset.data, asset.content_type),
            "contentType": asset.content_type,
            "sha256": asset.sha256,
            "bytes": len(asset.data),
        }
    return {"stems": assets, "sumCheck": check, "model": model}


def run_verify(job: queue.Job, store: ObjectStore) -> dict:
    """US-021: read the watermark and credentials of an uploaded file, then delete the upload."""
    key = str(job.payload["uploadKey"])
    data = store.get(key)
    try:
        return {"verify": verify.check(data)}
    finally:
        store.delete(key)


def run_job(
    job: queue.Job,
    store: ObjectStore,
    runner: str,
    acestep: AceStepRunner | None = None,
    finisher: Finisher | None = None,
    screen: Screen | None = None,
    writer: StubLyricWriter | LlmLyricWriter | None = None,
    lyria: LyriaRunner | None = None,
    separator: tuple[stems.Separator, str] | None = None,
) -> dict:
    """Runs one job and returns the result the API reads (JobFinalizer).

    Lyrics jobs (US-018) return the written lyrics. Song jobs render every take, screen it against
    the reference index (US-029) and finish it: with a finisher (the default in the worker process)
    each take becomes a watermarked, labelled and C2PA-signed MP3 192, MP3 320 and WAV. A take that
    matches a known recording is still finished, so an admin can review it, but marked withheld.
    Beat jobs (US-015) also get a tempo check on every take.
    """
    if job.kind == "lyrics":
        return lyrics.write(job.payload, writer or StubLyricWriter())
    if job.kind == "verify":
        return run_verify(job, store)
    if job.kind == "stems":
        sep, model = separator or (stems.stub_separate, "stub-bands")
        return run_stems(job, store, finisher or Finisher(), sep, model)
    if job.kind == "edit":
        return run_edit(job, store, runner, acestep, finisher or Finisher())
    stored = []
    for i, take in enumerate(render(job, runner, acestep, lyria), start=1):
        raw_key = store.put(f"takes/{job.id}/take-{i}.{take.format}", take.data, CONTENT_TYPES[take.format])
        entry: dict = {"key": raw_key, "format": take.format, "runner": runner}
        settings = job.payload.get("beat")
        if settings:
            entry["tempo"] = tempo_check(take, float(settings["bpm"]))
        if screen is not None:
            result = screen.check(take.data)
            entry["similarity"] = {k: v for k, v in result.items() if k != "withheld"}
            if result["withheld"]:
                entry["withheld"] = True
                entry["withheldReason"] = "similarity"
        if finisher is not None:
            finished = finisher.finish(take.data, job.payload, i)
            ext = {"mp3_192": "192.mp3", "mp3_320": "320.mp3", "wav": "master.wav"}
            entry["assets"] = {
                name: {
                    "key": store.put(f"takes/{job.id}/take-{i}-{ext[name]}", asset.data, asset.content_type),
                    "contentType": asset.content_type,
                    "sha256": asset.sha256,
                    "bytes": len(asset.data),
                }
                for name, asset in finished.assets.items()
            }
            entry["watermarkId"] = finished.watermark_id
            entry["durationSeconds"] = finished.seconds
            entry["c2pa"] = True
        stored.append(entry)
    if not stored:
        raise RuntimeError("runner returned no takes")
    result: dict = {"takes": stored}
    if job.payload.get("provider") == "lyria" and lyria is not None:
        result["costUsd"] = lyria.cost(len(stored))  # US-016: actual spend against the daily cap
    return result


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    dsn = os.environ["DATABASE_URL"]
    runner = os.environ.get("BEATMIND_RUNNER", "stub")
    kinds = os.environ.get("BEATMIND_JOB_KINDS", "song,beat,lyrics,edit,verify,stems").split(",")
    # US-016: "self" for our GPU workers; a worker with "lyria" runs the paid fallback.
    providers = os.environ.get("BEATMIND_PROVIDERS", "self").split(",")
    lease = int(os.environ.get("BEATMIND_LEASE_SECONDS", "600"))
    idle_sleep = float(os.environ.get("BEATMIND_IDLE_SLEEP", "1.0"))
    worker_id = f"{socket.gethostname()}:{os.getpid()}"
    acestep = None
    if runner == "acestep":
        # A take on a laptop or CPU can take several minutes; raise ACESTEP_TIMEOUT_SECONDS there.
        acestep = AceStepRunner(
            os.environ["ACESTEP_URL"],
            os.environ.get("ACESTEP_API_KEY", ""),
            timeout=float(os.environ.get("ACESTEP_TIMEOUT_SECONDS", "600")),
        )
    lyria = LyriaRunner.from_env() if "lyria" in providers else None
    # US-039: Demucs on the GPU worker; band-split stub locally.
    separator = stems.separator_from_env(os.environ.get("BEATMIND_STEMS", "stub"))
    # Watermark, AI label and C2PA on every take (REQ-F-30). Off only to debug the model runner.
    finisher = Finisher() if os.environ.get("BEATMIND_FINISHING", "on") != "off" else None
    # US-018: template lyrics unless a lyric model endpoint is configured.
    writer: StubLyricWriter | LlmLyricWriter = StubLyricWriter()
    if os.environ.get("BEATMIND_LYRICS_WRITER", "stub") == "llm":
        writer = LlmLyricWriter(
            os.environ["LYRICS_LLM_URL"],
            os.environ.get("LYRICS_LLM_MODEL", "qwen"),
            os.environ.get("LYRICS_LLM_API_KEY", ""),
        )
    # US-029: the similarity screen runs on every take unless switched off to debug the runner.
    screening = os.environ.get("BEATMIND_SIMILARITY", "on") != "off"
    store = ObjectStore()
    if os.environ.get("S3_CREATE_BUCKET") == "true":
        store.ensure_bucket()

    stopping = False

    def stop(*_: object) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    log.info("worker %s started (runner=%s, kinds=%s, lyrics=%s)", worker_id, runner, kinds, writer.model)
    with psycopg.connect(dsn) as conn, psycopg.connect(dsn) as screen_conn:
        screen = Screen(screen_conn) if screening else None
        while not stopping:
            queue.requeue_stale(conn, lease)
            job = queue.claim(conn, worker_id, kinds, providers)
            if job is None:
                time.sleep(idle_sleep)
                continue
            started = time.monotonic()
            try:
                result = run_job(job, store, runner, acestep, finisher, screen, writer, lyria, separator)
            except Exception as exc:  # noqa: BLE001 - any model failure is recorded on the job
                status = queue.fail(conn, job, f"{type(exc).__name__}: {exc}")
                log.warning("job %s failed (attempt %d, now %s): %s", job.id, job.attempts, status, exc)
                continue
            result["seconds"] = round(time.monotonic() - started, 3)
            queue.complete(conn, job, result)
            log.info("job %s succeeded in %.2fs", job.id, result["seconds"])
    log.info("worker %s stopped", worker_id)


if __name__ == "__main__":
    main()
