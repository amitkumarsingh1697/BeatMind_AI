-- US-007 / EXP-02: waitlist sign-ups and anonymous visit counts per price variant.

CREATE TABLE waitlist_signup (
    id                   uuid        PRIMARY KEY,
    email                text        NOT NULL,
    country              char(2)     NOT NULL,
    locale               text        NOT NULL,
    use_case             text,
    source_channel       text,
    price_variant        text        NOT NULL,
    consent_text_version text        NOT NULL,
    consented_at         timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX waitlist_signup_email_uq ON waitlist_signup (lower(email));

-- No personal data: one counter row per day, country, variant and channel.
CREATE TABLE waitlist_visit (
    day            date    NOT NULL,
    country        char(2) NOT NULL,
    price_variant  text    NOT NULL,
    source_channel text    NOT NULL DEFAULT '',
    visits         bigint  NOT NULL DEFAULT 0,
    PRIMARY KEY (day, country, price_variant, source_channel)
);
