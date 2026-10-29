"""Worker side of the PostgreSQL job queue (ARCH-03, ADR-005).

The API inserts rows into ``generation_job``; workers claim them with ``FOR UPDATE SKIP LOCKED`` so
many workers can poll the same table without double-processing a job.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

RETRY_BACKOFF_SECONDS = (30, 120, 600)


@dataclass(frozen=True)
class Job:
    id: str
    kind: str
    payload: dict[str, Any]
    attempts: int
    max_attempts: int


def claim(conn: psycopg.Connection, worker_id: str, kinds: list[str]) -> Job | None:
    """Claims the next queued job of the given kinds, or returns None if there is none."""
    row = conn.execute(
        """
        UPDATE generation_job
           SET status = 'running', attempts = attempts + 1,
               locked_by = %s, locked_at = now(), updated_at = now()
         WHERE id = (
                SELECT id FROM generation_job
                 WHERE status = 'queued' AND available_at <= now() AND kind = ANY(%s)
                 ORDER BY priority DESC, available_at, created_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1)
        RETURNING id, kind, payload, attempts, max_attempts
        """,
        (worker_id, kinds),
    ).fetchone()
    conn.commit()
    if row is None:
        return None
    return Job(id=str(row[0]), kind=row[1], payload=row[2], attempts=row[3], max_attempts=row[4])


def complete(conn: psycopg.Connection, job: Job, result: dict[str, Any]) -> None:
    conn.execute(
        """
        UPDATE generation_job
           SET status = 'succeeded', result = %s, error = NULL,
               locked_by = NULL, locked_at = NULL, updated_at = now()
         WHERE id = %s
        """,
        (Jsonb(result), job.id),
    )
    conn.commit()


def fail(conn: psycopg.Connection, job: Job, error: str) -> str:
    """Records a failure. Retries with backoff until max_attempts, then moves the job to 'dead'.

    Returns the job's new status.
    """
    if job.attempts >= job.max_attempts:
        status, delay = "dead", 0
    else:
        status = "queued"
        delay = RETRY_BACKOFF_SECONDS[min(job.attempts, len(RETRY_BACKOFF_SECONDS)) - 1]
    conn.execute(
        """
        UPDATE generation_job
           SET status = %s, error = %s, available_at = now() + make_interval(secs => %s),
               locked_by = NULL, locked_at = NULL, updated_at = now()
         WHERE id = %s
        """,
        (status, error[:2000], delay, job.id),
    )
    conn.commit()
    return status


def requeue_stale(conn: psycopg.Connection, lease_seconds: int) -> int:
    """Puts back jobs whose worker died mid-run (lease expired). Returns how many were requeued."""
    cur = conn.execute(
        """
        UPDATE generation_job
           SET status = CASE WHEN attempts >= max_attempts THEN 'dead' ELSE 'queued' END,
               error = 'worker lease expired', locked_by = NULL, locked_at = NULL, updated_at = now()
         WHERE status = 'running' AND locked_at < now() - make_interval(secs => %s)
        """,
        (lease_seconds,),
    )
    conn.commit()
    return cur.rowcount
