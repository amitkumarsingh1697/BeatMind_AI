-- Sprint 3: moderation (US-028), lyric writer (US-018), similarity screen (US-029), free commercial
-- licences and certificates (US-025), admin console with MFA (US-031), the Hindi interface
-- (US-010) and the 30-day purge of deleted songs (TDD 5.2).

-- Every blocked request, withheld take and appeal is a case an admin can see (REQ-F-50 to
-- REQ-F-52, REQ-F-59). Blocked text is not stored, only its hash and the rule that matched.
CREATE TABLE moderation_case (
    id            uuid        PRIMARY KEY,
    kind          text        NOT NULL CHECK (kind IN ('text_block', 'lyrics_block', 'similarity')),
    user_id       uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    song_id       uuid,
    take_idx      integer,
    category      text        NOT NULL,
    rule          text        NOT NULL,
    field         text,
    text_sha256   text,
    detail        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- open: recorded, nobody asked; appealed: the user asked for a human review (72-hour target);
    -- upheld or overturned: an admin decided.
    status        text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'appealed', 'upheld', 'overturned')),
    appeal_note   text,
    appealed_at   timestamptz,
    decided_by    uuid,
    decided_at    timestamptz,
    decision_note text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX moderation_case_queue_idx ON moderation_case (status, created_at);
CREATE INDEX moderation_case_user_idx ON moderation_case (user_id, created_at DESC);

-- US-018: lyrics the AI writes from a topic and mood. The job does the writing; this row holds
-- the moderated result the creator sees.
CREATE TABLE lyric_draft (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id     uuid        NOT NULL UNIQUE REFERENCES generation_job (id) ON DELETE CASCADE,
    topic      text        NOT NULL,
    mood       text        NOT NULL,
    language   text        NOT NULL,
    status     text        NOT NULL DEFAULT 'writing' CHECK (status IN ('writing', 'ready', 'blocked', 'failed')),
    lyrics     text,
    model      text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX lyric_draft_user_idx ON lyric_draft (user_id, created_at DESC);

-- The similarity result for each take (score and best reference), shown to admins.
ALTER TABLE take ADD COLUMN screen jsonb;

-- US-029, REQ-F-52: reference fingerprints of known recordings. Each row of similarity_hash is
-- one landmark (a pair of spectral peaks) at a time offset in the reference.
CREATE TABLE similarity_reference (
    id         bigserial   PRIMARY KEY,
    title      text        NOT NULL,
    artist     text,
    source     text        NOT NULL,
    seconds    real        NOT NULL,
    hashes     integer     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE similarity_hash (
    hash         integer NOT NULL,
    reference_id bigint  NOT NULL REFERENCES similarity_reference (id) ON DELETE CASCADE,
    t            integer NOT NULL
);

CREATE INDEX similarity_hash_idx ON similarity_hash (hash);

-- US-025, REQ-F-21, REQ-F-22: licence and rights certificates. The id is public (verify page).
CREATE TABLE certificate (
    id              text        PRIMARY KEY,
    song_id         uuid        NOT NULL REFERENCES song (id),
    licensee_user_id uuid       NOT NULL REFERENCES app_user (id),
    scope           text        NOT NULL CHECK (scope IN ('commercial_free', 'ownership_paid')),
    -- What the certificate shows, fixed when issued.
    title           text        NOT NULL,
    title_latin     text        NOT NULL,
    language_tag    text        NOT NULL,
    plan_at_creation text       NOT NULL,
    song_created_at timestamptz NOT NULL,
    human_lyrics    boolean     NOT NULL,
    master_sha256   text,
    -- Calendar month in India time that this licence counts against (free plan, 5 a month).
    quota_month     text,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    revoked_at      timestamptz,
    revoked_reason  text,
    UNIQUE (song_id, scope)
);

CREATE INDEX certificate_quota_idx ON certificate (licensee_user_id, quota_month) WHERE scope = 'commercial_free';

-- US-031, REQ-SEC-06, TM-10: admins must use a second factor. TOTP secrets are encrypted with a
-- key derived from the server secret; recovery codes are stored as hashes.
CREATE TABLE user_mfa (
    user_id          uuid        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    totp_secret_enc  text        NOT NULL,
    confirmed_at     timestamptz,
    last_step        bigint      NOT NULL DEFAULT 0,
    failures         integer     NOT NULL DEFAULT 0,
    failures_since   timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE mfa_recovery_code (
    user_id   uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash text        NOT NULL,
    used_at   timestamptz,
    PRIMARY KEY (user_id, code_hash)
);

-- A session that passed the second factor.
ALTER TABLE user_session ADD COLUMN mfa_at timestamptz;

-- Admins can pause an account (no new songs) while they look at a case.
ALTER TABLE app_user ADD COLUMN suspended_at timestamptz;

-- TDD 5.2: files of deleted songs are removed 30 days after deletion.
ALTER TABLE song ADD COLUMN purged_at timestamptz;
CREATE INDEX song_purge_idx ON song (deleted_at) WHERE deleted_at IS NOT NULL AND purged_at IS NULL;
