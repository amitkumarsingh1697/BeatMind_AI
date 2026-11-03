-- Sprint 4: takedowns and appeals with deadlines (US-032), data export and account deletion
-- (US-011), beat mode (US-015), sharing (US-024) and queue estimates (US-014).

-- US-032, REQ-F-54, REQ-F-59: rights and abuse reports (API-013). Every report has two clocks:
-- acknowledge within 24 hours, act within 3 hours for Indian government or court orders and
-- within 15 days otherwise (Copyright and Takedown Policy, sections 2 and 3). The reporter's
-- contact details are kept for the legal process only (DPIA: support and reports, 3 years).
CREATE TABLE takedown_request (
    id               uuid        PRIMARY KEY,
    song_id          uuid        REFERENCES song (id) ON DELETE SET NULL,
    certificate_id   text,
    reporter_kind    text        NOT NULL CHECK (reporter_kind IN ('rights_holder', 'user', 'government_order', 'court_order')),
    reporter_name    text        NOT NULL,
    reporter_email   text        NOT NULL,
    reporter_user_id uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    right_claimed    text        NOT NULL CHECK (right_claimed IN ('copyright', 'voice', 'name_or_likeness', 'privacy', 'unlawful', 'other')),
    description      text        NOT NULL,
    evidence_url     text,
    order_reference  text,
    client_ip_hash   text,
    -- received: waiting for a person; acknowledged: a person has it; actioned: the song was
    -- disabled; rejected: not valid on its face; appealed: the owner asked for a review of the
    -- removal; restored: the appeal succeeded.
    status           text        NOT NULL DEFAULT 'received'
                                 CHECK (status IN ('received', 'acknowledged', 'actioned', 'rejected', 'appealed', 'restored')),
    ack_due_at       timestamptz NOT NULL,
    action_due_at    timestamptz NOT NULL,
    acknowledged_at  timestamptz,
    decided_at       timestamptz,
    decided_by       uuid,
    decision_note    text,
    -- The song's rights before it was disabled, so a successful appeal can put them back.
    previous_rights  text,
    appeal_note      text,
    appealed_at      timestamptz,
    appeal_due_at    timestamptz,
    appeal_decided_at timestamptz,
    appeal_decided_by uuid,
    appeal_decision_note text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX takedown_open_idx ON takedown_request (status, action_due_at);
CREATE INDEX takedown_song_idx ON takedown_request (song_id, created_at DESC);
CREATE INDEX takedown_ip_idx ON takedown_request (client_ip_hash, created_at DESC);

-- US-024, FEAT-13, REQ-SEC-03: share links. Only a hash of the token is stored; the link holder
-- needs the token itself. A private link plays the song for anyone who has it; a public link also
-- lets the page be indexed. Revoking stops it at once.
CREATE TABLE share_link (
    id          uuid        PRIMARY KEY,
    song_id     uuid        NOT NULL REFERENCES song (id) ON DELETE CASCADE,
    token_hash  text        NOT NULL UNIQUE,
    token_hint  text        NOT NULL,
    visibility  text        NOT NULL CHECK (visibility IN ('private', 'public')),
    take_idx    integer     NOT NULL,
    views       integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);

CREATE INDEX share_link_song_idx ON share_link (song_id) WHERE revoked_at IS NULL;

-- US-015, REQ-F-04: beat mode. The settings live with the song; the worker's tempo check result
-- lives with each take.
ALTER TABLE song DROP CONSTRAINT song_mode_check;
ALTER TABLE song ADD CONSTRAINT song_mode_check CHECK (mode IN ('vibe', 'lyrics', 'beat'));
ALTER TABLE song ADD COLUMN beat jsonb;
ALTER TABLE take ADD COLUMN analysis jsonb;

-- US-011, DPIA section 6: a closed account keeps a tombstone row (no email, no Google id, no
-- birth month) so certificates other people rely on still resolve and the ledger stays intact.
ALTER TABLE app_user ADD COLUMN deleted_at timestamptz;
