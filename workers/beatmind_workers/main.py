"""Worker process: poll the queue, run the model, store audio, record the result."""

from __future__ import annotations

import logging
import os
import signal
import socket
import time

import psycopg

from beatmind_workers import queue, stub
from beatmind_workers.acestep import AceStepRunner, FetchedTake
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


def run_job(job: queue.Job, store: ObjectStore, runner: str, acestep: AceStepRunner | None = None) -> dict:
    """Renders and stores every take, then returns the result the API reads (GenerationStore)."""
    stored = []
    for i, take in enumerate(render(job, runner, acestep), start=1):
        key = store.put(f"takes/{job.id}/take-{i}.{take.format}", take.data, CONTENT_TYPES[take.format])
        stored.append({"key": key, "format": take.format, "runner": runner})
    if not stored:
        raise RuntimeError("runner returned no takes")
    return {"takes": stored}


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    dsn = os.environ["DATABASE_URL"]
    runner = os.environ.get("BEATMIND_RUNNER", "stub")
    kinds = os.environ.get("BEATMIND_JOB_KINDS", "song,beat").split(",")
    lease = int(os.environ.get("BEATMIND_LEASE_SECONDS", "600"))
    idle_sleep = float(os.environ.get("BEATMIND_IDLE_SLEEP", "1.0"))
    worker_id = f"{socket.gethostname()}:{os.getpid()}"
    acestep = None
    if runner == "acestep":
        acestep = AceStepRunner(os.environ["ACESTEP_URL"], os.environ.get("ACESTEP_API_KEY", ""))
    store = ObjectStore()
    if os.environ.get("S3_CREATE_BUCKET") == "true":
        store.ensure_bucket()

    stopping = False

    def stop(*_: object) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    log.info("worker %s started (runner=%s, kinds=%s)", worker_id, runner, kinds)
    with psycopg.connect(dsn) as conn:
        while not stopping:
            queue.requeue_stale(conn, lease)
            job = queue.claim(conn, worker_id, kinds)
            if job is None:
                time.sleep(idle_sleep)
                continue
            started = time.monotonic()
            try:
                result = run_job(job, store, runner, acestep)
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
