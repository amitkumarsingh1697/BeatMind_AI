"""Worker process: poll the queue, run the model, store audio, record the result."""

from __future__ import annotations

import logging
import os
import signal
import socket
import time

import psycopg

from beatmind_workers import queue, stub
from beatmind_workers.storage import ObjectStore

log = logging.getLogger("beatmind.worker")


def run_job(job: queue.Job, store: ObjectStore, runner: str) -> dict:
    if runner != "stub":
        raise NotImplementedError(
            f"runner {runner!r} is not available yet; the ACE-Step runner lands in Sprint 1"
        )
    prompt = str(job.payload.get("prompt", ""))
    audio = stub.render_tone(prompt)
    key = store.put(f"takes/{job.id}/take-1.wav", audio, "audio/wav")
    return {"takes": [{"key": key, "format": "wav", "runner": runner}]}


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    dsn = os.environ["DATABASE_URL"]
    runner = os.environ.get("BEATMIND_RUNNER", "stub")
    kinds = os.environ.get("BEATMIND_JOB_KINDS", "song,beat").split(",")
    lease = int(os.environ.get("BEATMIND_LEASE_SECONDS", "600"))
    idle_sleep = float(os.environ.get("BEATMIND_IDLE_SLEEP", "1.0"))
    worker_id = f"{socket.gethostname()}:{os.getpid()}"
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
                result = run_job(job, store, runner)
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
