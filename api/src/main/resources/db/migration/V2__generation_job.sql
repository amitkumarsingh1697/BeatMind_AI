-- ARCH-03 / ADR-005: PostgreSQL-backed job queue. Workers claim with FOR UPDATE SKIP LOCKED.

CREATE TABLE generation_job (
    id           uuid        PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('song', 'beat', 'lyrics')),
    status       text        NOT NULL DEFAULT 'queued'
                             CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'dead')),
    priority     integer     NOT NULL DEFAULT 0,
    payload      jsonb       NOT NULL,
    attempts     integer     NOT NULL DEFAULT 0,
    max_attempts integer     NOT NULL DEFAULT 3,
    available_at timestamptz NOT NULL DEFAULT now(),
    locked_by    text,
    locked_at    timestamptz,
    result       jsonb,
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Claim order: highest priority first, then oldest.
CREATE INDEX generation_job_claim_idx
    ON generation_job (priority DESC, available_at, created_at)
    WHERE status = 'queued';
