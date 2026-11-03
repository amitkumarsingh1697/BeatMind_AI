-- Sprint 6: Paddle as the merchant of record outside India (US-035, ADR-009) and separated stems
-- (US-039, REQ-F-23).

-- US-035: subscriptions outside India are billed by Paddle, which collects and remits sales tax.
ALTER TABLE subscription DROP CONSTRAINT subscription_provider_check;
ALTER TABLE subscription ADD CONSTRAINT subscription_provider_check CHECK (provider IN ('razorpay', 'paddle'));

-- US-039: four stems (vocals, drums, bass, other) of one version of a take. assets holds a WAV per
-- stem, each watermarked, labelled and signed like a take; sum_check is the separation's sum test.
CREATE TABLE take_stems (
    id         uuid        PRIMARY KEY,
    song_id    uuid        NOT NULL REFERENCES song (id) ON DELETE CASCADE,
    take_idx   integer     NOT NULL,
    version    integer     NOT NULL,
    status     text        NOT NULL CHECK (status IN ('processing', 'ready', 'failed')),
    job_id     uuid        UNIQUE,
    assets     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    sum_check  jsonb,
    model      text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (song_id, take_idx, version)
);

ALTER TABLE generation_job DROP CONSTRAINT generation_job_kind_check;
ALTER TABLE generation_job ADD CONSTRAINT generation_job_kind_check
    CHECK (kind IN ('song', 'beat', 'lyrics', 'edit', 'verify', 'stems'));
