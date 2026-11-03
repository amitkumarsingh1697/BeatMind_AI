import uuid

import psycopg
import pytest

from beatmind_workers import queue
from tests.conftest import DSN, SCHEMA

pytestmark = pytest.mark.integration


def enqueue(conn, kind="song", priority=0, max_attempts=3, prompt="chai lofi"):
    job_id = uuid.uuid4()
    conn.execute(
        "INSERT INTO generation_job (id, kind, priority, max_attempts, payload) "
        "VALUES (%s, %s, %s, %s, jsonb_build_object('prompt', %s::text))",
        (job_id, kind, priority, max_attempts, prompt),
    )
    conn.commit()
    return str(job_id)


def status(conn, job_id):
    return conn.execute("SELECT status FROM generation_job WHERE id = %s", (job_id,)).fetchone()[0]


def test_claims_highest_priority_first(conn):
    low = enqueue(conn, priority=0)
    high = enqueue(conn, priority=10)

    assert queue.claim(conn, "w1", ["song"]).id == high
    assert queue.claim(conn, "w1", ["song"]).id == low
    assert queue.claim(conn, "w1", ["song"]) is None


def test_claim_respects_job_kind(conn):
    enqueue(conn, kind="lyrics")

    assert queue.claim(conn, "w1", ["song", "beat"]) is None


def test_two_workers_never_get_the_same_job(conn):
    only = enqueue(conn)
    other = psycopg.connect(DSN, options=f"-c search_path={SCHEMA}")
    try:
        with other.transaction():
            # Worker 2 holds a row lock on the job, as it would mid-claim.
            other.execute("SELECT id FROM generation_job WHERE id = %s FOR UPDATE", (only,))
            assert queue.claim(conn, "w1", ["song"]) is None
    finally:
        other.close()
    assert queue.claim(conn, "w1", ["song"]).id == only


def test_complete_stores_result(conn):
    job_id = enqueue(conn)
    job = queue.claim(conn, "w1", ["song"])

    queue.complete(conn, job, {"takes": [{"key": "k"}]})

    row = conn.execute(
        "SELECT status, result->'takes'->0->>'key' FROM generation_job WHERE id = %s", (job_id,)
    ).fetchone()
    assert row == ("succeeded", "k")


def test_failure_retries_with_backoff_then_goes_dead(conn):
    job_id = enqueue(conn, max_attempts=2)

    job = queue.claim(conn, "w1", ["song"])
    assert queue.fail(conn, job, "boom") == "queued"
    # Backoff: not claimable straight away.
    assert queue.claim(conn, "w1", ["song"]) is None

    conn.execute("UPDATE generation_job SET available_at = now() WHERE id = %s", (job_id,))
    conn.commit()
    job = queue.claim(conn, "w1", ["song"])
    assert job.attempts == 2
    assert queue.fail(conn, job, "boom again") == "dead"
    assert status(conn, job_id) == "dead"


def test_stale_running_job_is_requeued(conn):
    job_id = enqueue(conn)
    queue.claim(conn, "w1", ["song"])
    conn.execute("UPDATE generation_job SET locked_at = now() - interval '1 hour' WHERE id = %s", (job_id,))
    conn.commit()

    assert queue.requeue_stale(conn, lease_seconds=600) == 1
    assert status(conn, job_id) == "queued"


def timings(conn, job_id):
    return conn.execute(
        "SELECT started_at IS NOT NULL, finished_at IS NOT NULL FROM generation_job WHERE id = %s", (job_id,)
    ).fetchone()


def test_records_start_and_finish_times(conn):
    job_id = enqueue(conn)
    job = queue.claim(conn, "w1", ["song"])
    assert timings(conn, job_id) == (True, False)

    queue.complete(conn, job, {"takes": []})

    assert timings(conn, job_id) == (True, True)


def test_dead_job_gets_a_finish_time_but_a_retry_does_not(conn):
    job_id = enqueue(conn, max_attempts=2)
    queue.fail(conn, queue.claim(conn, "w1", ["song"]), "boom")
    assert timings(conn, job_id) == (True, False)

    conn.execute("UPDATE generation_job SET available_at = now() WHERE id = %s", (job_id,))
    conn.commit()
    queue.fail(conn, queue.claim(conn, "w1", ["song"]), "boom again")

    assert status(conn, job_id) == "dead"
    assert timings(conn, job_id) == (True, True)


def test_fallback_jobs_go_only_to_fallback_workers(conn):
    """US-016: a job the API routed to the Lyria fallback is never claimed by a GPU worker."""
    routed = enqueue(conn)
    conn.execute("UPDATE generation_job SET provider = 'lyria' WHERE id = %s", (routed,))
    conn.commit()
    assert queue.claim(conn, "gpu", ["song"]) is None
    assert queue.claim(conn, "gpu", ["song"], ["self"]) is None
    assert queue.claim(conn, "lyria-1", ["song"], ["lyria"]).id == routed
