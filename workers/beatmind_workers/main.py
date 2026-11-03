"""Worker process: poll the queue, run the model, store audio, record the result."""

from __future__ import annotations

import logging
import os
import signal
import socket
import time

import psycopg

from beatmind_workers import lyrics, queue, stub
from beatmind_workers.acestep import AceStepRunner, FetchedTake
from beatmind_workers.finishing import Finisher
from beatmind_workers.lyrics import LlmLyricWriter, StubLyricWriter
from beatmind_workers.similarity import Screen
from beatmind_workers.storage import ObjectStore

log = logging.getLogger("beatmind.worker")

TAKES_PER_REQUEST = 2  # REQ-F-05
CONTENT_TYPES = {"wav": "audio/wav", "mp3": "audio/mpeg"}


def render(job: queue.Job, runner: str, acestep: AceStepRunner | None) -> list[FetchedTake]:
    """Makes the takes for a job with the configured runner."""
    takes = int(job.payload.get("takes", TAKES_PER_REQUEST))
    if runner == "stub":
        prompt = str(job.payload.get("prompt", ""))
        return [FetchedTake(stub.render_tone(f"{prompt}#take{i}"), "wav") for i in range(1, takes + 1)]
    if runner == "acestep" and acestep is not None:
        return acestep.render(job.payload)
    raise ValueError(f"unknown runner {runner!r}; use stub or acestep")


def run_job(
    job: queue.Job,
    store: ObjectStore,
    runner: str,
    acestep: AceStepRunner | None = None,
    finisher: Finisher | None = None,
    screen: Screen | None = None,
    writer: StubLyricWriter | LlmLyricWriter | None = None,
) -> dict:
    """Runs one job and returns the result the API reads (JobFinalizer).

    Lyrics jobs (US-018) return the written lyrics. Song jobs render every take, screen it against
    the reference index (US-029) and finish it: with a finisher (the default in the worker process)
    each take becomes a watermarked, labelled and C2PA-signed MP3 192, MP3 320 and WAV. A take that
    matches a known recording is still finished, so an admin can review it, but marked withheld.
    """
    if job.kind == "lyrics":
        return lyrics.write(job.payload, writer or StubLyricWriter())
    stored = []
    for i, take in enumerate(render(job, runner, acestep), start=1):
        raw_key = store.put(f"takes/{job.id}/take-{i}.{take.format}", take.data, CONTENT_TYPES[take.format])
        entry: dict = {"key": raw_key, "format": take.format, "runner": runner}
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
    return {"takes": stored}


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    dsn = os.environ["DATABASE_URL"]
    runner = os.environ.get("BEATMIND_RUNNER", "stub")
    kinds = os.environ.get("BEATMIND_JOB_KINDS", "song,beat,lyrics").split(",")
    lease = int(os.environ.get("BEATMIND_LEASE_SECONDS", "600"))
    idle_sleep = float(os.environ.get("BEATMIND_IDLE_SLEEP", "1.0"))
    worker_id = f"{socket.gethostname()}:{os.getpid()}"
    acestep = None
    if runner == "acestep":
        acestep = AceStepRunner(os.environ["ACESTEP_URL"], os.environ.get("ACESTEP_API_KEY", ""))
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
            job = queue.claim(conn, worker_id, kinds)
            if job is None:
                time.sleep(idle_sleep)
                continue
            started = time.monotonic()
            try:
                result = run_job(job, store, runner, acestep, finisher, screen, writer)
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
