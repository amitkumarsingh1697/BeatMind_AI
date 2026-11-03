-- Sprint 5: prices by billing country (US-033), Razorpay payments (US-034), ownership that vests
-- when a payment settles and the chargeback rule (US-036), cancellation (US-037), the song editor
-- (US-038), the public verify page (US-021) and the paid API fallback with a spend cap (US-016).

-- US-033, REQ-F-41: the billing country decides the price, not the browsing country.
ALTER TABLE app_user ADD COLUMN billing_country text CHECK (billing_country ~ '^[A-Z]{2}$');

-- US-034: one subscription per checkout. Razorpay holds the card or UPI mandate; we hold the state
-- that decides plans and rights. status follows Razorpay's subscription states.
CREATE TABLE subscription (
    id                       uuid        PRIMARY KEY,
    user_id                  uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    plan                     text        NOT NULL CHECK (plan IN ('creator', 'pro')),
    billing_country          text        NOT NULL,
    price_band               text,
    variant                  text        NOT NULL DEFAULT 'A',
    currency                 text        NOT NULL,
    amount_minor             bigint      NOT NULL,
    provider                 text        NOT NULL CHECK (provider IN ('razorpay')),
    provider_subscription_id text        NOT NULL UNIQUE,
    status                   text        NOT NULL DEFAULT 'created'
                                         CHECK (status IN ('created', 'authenticated', 'active', 'pending', 'halted',
                                                           'cancelled', 'completed', 'expired')),
    cancel_at_period_end     boolean     NOT NULL DEFAULT false,
    cancel_requested_at      timestamptz,
    current_start            timestamptz,
    current_end              timestamptz,
    ended_at                 timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX subscription_user_idx ON subscription (user_id, created_at DESC);
-- One live subscription per account.
CREATE UNIQUE INDEX subscription_one_live_idx ON subscription (user_id)
    WHERE status IN ('created', 'authenticated', 'active', 'pending');

-- REQ-F-57: one row per paid billing period. Songs made in a period vest when its payment settles;
-- a chargeback or refund reverses the period and the songs made in it.
CREATE TABLE billing_period (
    id                  uuid        PRIMARY KEY,
    subscription_id     uuid        NOT NULL REFERENCES subscription (id) ON DELETE CASCADE,
    user_id             uuid        NOT NULL,
    plan                text        NOT NULL,
    starts_at           timestamptz NOT NULL,
    ends_at             timestamptz NOT NULL,
    provider_payment_id text        UNIQUE,
    amount_minor        bigint      NOT NULL,
    currency            text        NOT NULL,
    payment_method      text,
    status              text        NOT NULL CHECK (status IN ('settled', 'reversed')),
    settled_at          timestamptz,
    reversed_at         timestamptz,
    reversal_reason     text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX billing_period_user_idx ON billing_period (user_id, starts_at DESC);

-- REQ-SEC-08: every webhook is processed once, even when the provider retries it.
CREATE TABLE payment_webhook (
    provider       text        NOT NULL,
    event_id       text        NOT NULL,
    event_type     text        NOT NULL,
    body_sha256    text        NOT NULL,
    outcome        text        NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_id)
);

-- US-036: a song made on a paid plan is "vesting" until the payment for its period settles.
ALTER TABLE song DROP CONSTRAINT song_rights_status_check;
ALTER TABLE song ADD CONSTRAINT song_rights_status_check
    CHECK (rights_status IN ('bm_owned', 'free_licensed', 'vesting', 'user_owned', 'disabled'));
ALTER TABLE song ADD COLUMN billing_period_id uuid REFERENCES billing_period (id) ON DELETE SET NULL;
ALTER TABLE song ADD COLUMN subscription_id uuid REFERENCES subscription (id) ON DELETE SET NULL;
CREATE INDEX song_vesting_idx ON song (subscription_id) WHERE rights_status = 'vesting';
CREATE INDEX song_period_idx ON song (billing_period_id);

-- US-038, REQ-F-10, REQ-F-11: every edit makes a new version of a take. Version 1 is the take as
-- generated; take.assets always holds the files of the current version.
CREATE TABLE take_version (
    id            uuid        PRIMARY KEY,
    song_id       uuid        NOT NULL REFERENCES song (id) ON DELETE CASCADE,
    take_idx      integer     NOT NULL,
    version       integer     NOT NULL,
    parent        integer,
    op            text        NOT NULL CHECK (op IN ('original', 'trim', 'fade', 'extend', 'regenerate')),
    params        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status        text        NOT NULL CHECK (status IN ('processing', 'ready', 'failed')),
    job_id        uuid        UNIQUE,
    assets        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- The unwatermarked render the next edit starts from, so marks are never stacked.
    raw_key       text,
    watermark_id  bigint,
    duration_s    numeric(8, 2),
    seam          jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (song_id, take_idx, version)
);
ALTER TABLE take ADD COLUMN current_version integer NOT NULL DEFAULT 1;

-- US-021, REQ-F-32, REQ-SEC-10: verify checks. The uploaded file is deleted once read; only its
-- hash and the coarse answer are kept, for the rate limit and abuse review.
CREATE TABLE verify_check (
    id             uuid        PRIMARY KEY,
    kind           text        NOT NULL CHECK (kind IN ('file', 'certificate')),
    file_sha256    text,
    upload_key     text,
    job_id         uuid        UNIQUE,
    status         text        NOT NULL CHECK (status IN ('checking', 'done', 'failed')),
    result         jsonb,
    client_ip_hash text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX verify_check_ip_idx ON verify_check (client_ip_hash, created_at);

-- New job kinds: edits run the model or ffmpeg on one take; verify reads a watermark and C2PA.
-- US-016: provider says which runner may claim a job; 'lyria' is the paid fallback API.
ALTER TABLE generation_job DROP CONSTRAINT generation_job_kind_check;
ALTER TABLE generation_job ADD CONSTRAINT generation_job_kind_check
    CHECK (kind IN ('song', 'beat', 'lyrics', 'edit', 'verify'));
ALTER TABLE generation_job ADD COLUMN provider text NOT NULL DEFAULT 'self' CHECK (provider IN ('self', 'lyria'));
ALTER TABLE generation_job ADD COLUMN cost_estimate_usd numeric(10, 4);
ALTER TABLE generation_job ADD COLUMN cost_actual_usd numeric(10, 4);
CREATE INDEX generation_job_provider_day_idx ON generation_job (provider, created_at) WHERE provider <> 'self';
