-- Sprint 2: songs and takes (US-017, US-022), provenance ledger (US-019), credit ledger and refunds
-- (US-027), plans and caps (US-030). See TDD sections 5 and 6.

-- Plan decides concurrency, allowance and download formats (entitlement matrix). Payments come in
-- Sprints 5 and 6; until then an admin sets the plan. daily_cap_override lets an admin give one
-- free account a different daily allowance (REQ-F-55).
ALTER TABLE app_user
    ADD COLUMN plan               text    NOT NULL DEFAULT 'free' CHECK (plan IN ('free', 'creator', 'pro')),
    ADD COLUMN daily_cap_override integer CHECK (daily_cap_override >= 0);

-- A song is what the creator sees in the library. Its generation job makes the takes.
-- Text is stored in NFC; titles also in Latin script with a BCP 47 language tag (REQ-F-08).
CREATE TABLE song (
    id               uuid        PRIMARY KEY,
    owner_id         uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id           uuid        UNIQUE REFERENCES generation_job (id) ON DELETE SET NULL,
    mode             text        NOT NULL CHECK (mode IN ('vibe', 'lyrics')),
    title            text        NOT NULL,
    title_latin      text        NOT NULL,
    language_tag     text        NOT NULL,
    prompt           text        NOT NULL,
    lyrics           text,
    vocal_type       text,
    instrumental     boolean     NOT NULL DEFAULT false,
    duration_seconds integer     NOT NULL,
    -- Rights are fixed by the plan at creation (REQ-F-25, TDD 6.3).
    plan_at_creation text        NOT NULL,
    rights_status    text        NOT NULL CHECK (rights_status IN ('bm_owned', 'free_licensed', 'user_owned', 'disabled')),
    -- Lower-case NFC text and a loose Latin "sounds like" key, so "raat ka safar" finds "रात का सफ़र".
    search_text      text        NOT NULL,
    search_key       text        NOT NULL,
    client_ip_hash   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    -- Deleted songs leave the library at once and are purged after 30 days (TDD 5.2).
    deleted_at       timestamptz
);

CREATE INDEX song_owner_idx ON song (owner_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX song_ip_idx ON song (client_ip_hash, created_at DESC);

CREATE TABLE take (
    id           uuid        PRIMARY KEY,
    song_id      uuid        NOT NULL REFERENCES song (id) ON DELETE CASCADE,
    idx          integer     NOT NULL,
    status       text        NOT NULL CHECK (status IN ('ready', 'withheld', 'failed')),
    -- Payload of the inaudible watermark, linked to this take (TDD 6.2).
    watermark_id bigint,
    -- Exported files by format (mp3_192, mp3_320, wav): storage key, sha256, bytes, content type.
    assets       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (song_id, idx)
);

-- The API turns finished jobs into takes, ledger events and refunds once (JobFinalizer).
ALTER TABLE generation_job ADD COLUMN finalized_at timestamptz;
CREATE INDEX generation_job_unfinalized_idx ON generation_job (finished_at)
    WHERE finalized_at IS NULL AND status IN ('succeeded', 'dead');

-- Append-only tables reject UPDATE and DELETE. In production the API role also has no TRUNCATE.
CREATE FUNCTION beatmind_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- US-019, REQ-F-31: hash-chained provenance ledger, one chain per song.
-- hash = SHA-256(prev_hash || body), where body is the canonical JSON of the event.
-- No foreign key on song_id: the ledger is evidence and outlives a deleted song or account.
CREATE TABLE provenance_event (
    id         bigserial   PRIMARY KEY,
    song_id    uuid        NOT NULL,
    seq        integer     NOT NULL,
    type       text        NOT NULL,
    actor      text        NOT NULL CHECK (actor IN ('human', 'ai', 'system')),
    body       text        NOT NULL,
    prev_hash  text        NOT NULL,
    hash       text        NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (song_id, seq)
);

CREATE TRIGGER provenance_event_append_only BEFORE UPDATE OR DELETE ON provenance_event
    FOR EACH ROW EXECUTE FUNCTION beatmind_append_only();

-- US-027, REQ-F-56: credit ledger. 1 credit = 1 take; a request debits 2 and each failed,
-- timed-out or withheld take is refunded automatically.
CREATE TABLE credit_ledger (
    id            bigserial   PRIMARY KEY,
    user_id       uuid        NOT NULL,
    delta         integer     NOT NULL,
    reason        text        NOT NULL CHECK (reason IN ('generation', 'refund_failed', 'refund_withheld')),
    job_id        uuid,
    song_id       uuid,
    balance_after integer     NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX credit_ledger_user_idx ON credit_ledger (user_id, created_at DESC);
CREATE INDEX credit_ledger_job_idx ON credit_ledger (job_id);

CREATE TRIGGER credit_ledger_append_only BEFORE UPDATE OR DELETE ON credit_ledger
    FOR EACH ROW EXECUTE FUNCTION beatmind_append_only();

-- Settings an admin can change at run time (REQ-F-55 daily cap).
CREATE TABLE app_setting (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid
);

-- Admin audit rows can say more than the target (old and new cap, for example).
ALTER TABLE admin_audit ADD COLUMN detail text;

-- Songs for jobs made before this migration, so the library shows them. They have no ledger
-- history before now, and no labelled downloads (their takes were made before labelling existed).
INSERT INTO song (id, owner_id, job_id, mode, title, title_latin, language_tag, prompt, instrumental,
                  duration_seconds, plan_at_creation, rights_status, search_text, search_key, created_at)
SELECT gen_random_uuid(), j.user_id, j.id, 'vibe',
       left(j.payload ->> 'prompt', 60), left(j.payload ->> 'prompt', 60),
       CASE j.payload ->> 'language' WHEN 'hi' THEN 'hi' WHEN 'hinglish' THEN 'hi-Latn' ELSE 'en' END,
       coalesce(j.payload ->> 'prompt', ''), coalesce((j.payload ->> 'instrumental')::boolean, false),
       coalesce((j.payload ->> 'durationSeconds')::integer, 60), 'free', 'bm_owned',
       lower(coalesce(j.payload ->> 'prompt', '')), lower(coalesce(j.payload ->> 'prompt', '')), j.created_at
  FROM generation_job j
 WHERE j.user_id IS NOT NULL AND j.kind = 'song';

INSERT INTO take (id, song_id, idx, status, assets)
SELECT gen_random_uuid(), s.id, t.ord, 'ready',
       jsonb_build_object('preview', jsonb_build_object(
           'key', t.value ->> 'key',
           'contentType', CASE t.value ->> 'format' WHEN 'mp3' THEN 'audio/mpeg' ELSE 'audio/wav' END))
  FROM song s
  JOIN generation_job j ON j.id = s.job_id
 CROSS JOIN LATERAL jsonb_array_elements(j.result -> 'takes') WITH ORDINALITY AS t (value, ord)
 WHERE j.status = 'succeeded';

-- Old jobs were never charged, so they are never refunded either.
UPDATE generation_job SET finalized_at = now() WHERE status IN ('succeeded', 'dead');
