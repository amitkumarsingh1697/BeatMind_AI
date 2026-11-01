-- US-009 sign-in (ADR-008, REQ-SEC-01, REQ-F-53) and US-012 generation ownership.

CREATE TABLE app_user (
    id              uuid        PRIMARY KEY,
    email           text        NOT NULL,
    google_sub      text        UNIQUE,
    -- 18+ rule (REQ-F-53). The user declares a full date of birth; the day is used for the check
    -- and then dropped, so only the first day of the birth month is kept (DPIA section 2).
    -- NULL until the profile step is done.
    birth_month     date,
    terms_version   text,
    locale          text        NOT NULL DEFAULT 'en',
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_sign_in_at timestamptz
);

CREATE UNIQUE INDEX app_user_email_uq ON app_user (lower(email));

-- One-time sign-in codes. Only an HMAC of the code is stored; codes expire after 10 minutes and
-- allow 5 attempts (threat model TM-01).
CREATE TABLE email_code (
    id         uuid        PRIMARY KEY,
    email      text        NOT NULL,
    code_hash  text        NOT NULL,
    ip_hash    text        NOT NULL,
    attempts   integer     NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX email_code_email_idx ON email_code (lower(email), created_at DESC);
CREATE INDEX email_code_ip_idx ON email_code (ip_hash, created_at DESC);

-- Server-side sessions. The cookie holds a random token; only its SHA-256 is stored (TM-02).
CREATE TABLE user_session (
    token_hash   text        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL
);

CREATE INDEX user_session_user_idx ON user_session (user_id);

-- Google sign-in round trips: state, nonce and PKCE verifier, kept for 10 minutes.
CREATE TABLE oauth_state (
    state_hash    text        PRIMARY KEY,
    nonce         text        NOT NULL,
    code_verifier text        NOT NULL,
    locale        text        NOT NULL,
    expires_at    timestamptz NOT NULL
);

-- Who asked for each job, and timings for the time-to-playable SLO (REQ-F-01, US-004).
ALTER TABLE generation_job
    ADD COLUMN user_id     uuid REFERENCES app_user (id) ON DELETE CASCADE,
    ADD COLUMN started_at  timestamptz,
    ADD COLUMN finished_at timestamptz;

CREATE INDEX generation_job_user_idx ON generation_job (user_id, created_at DESC);
CREATE INDEX generation_job_finished_idx ON generation_job (finished_at) WHERE finished_at IS NOT NULL;

-- Admin actions (US-013 dead-letter retries for now; the full console comes with US-031).
CREATE TABLE admin_audit (
    id            uuid        PRIMARY KEY,
    actor_user_id uuid        NOT NULL,
    action        text        NOT NULL,
    target        text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
