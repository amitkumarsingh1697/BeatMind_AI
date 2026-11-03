# Sprint 3 report: safe and multilingual

| Item | Value |
| --- | --- |
| Sprint | SPRINT-03, started early on 2026-11-03 |
| Goal | Safe and multilingual: Hindi interface, AI lyrics, licences, moderation, similarity screen, admin console |
| Extra scope | Sprint 2 carry-overs: beta terms text (version `2026-11-beta`) and the 30-day file purge (founder, 2026-11-03) |
| Points | 42 planned, 42 built locally (US-040 as a kit: the test itself needs listeners) |
| Status | Code done and tested locally. Listeners, native review, counsel sign-off, a reference catalogue and a lyric model are outside steps in [TODO.md](../../TODO.md) |

## What a developer can do now

Run `docker compose up --build`, open http://localhost:3000/en/create and:

1. switch the whole app to Hindi from the header (हिन्दी) and back; the choice is remembered on the
   account and for the next visit;
2. ask for "a song in the voice of Arijit Singh" and see it refused with the reason, then ask a
   person to review it;
3. in **Use my lyrics**, open **Help me write lyrics**, give a topic and mood, and use or edit what
   the AI wrote; the song's history says the AI wrote the lyrics and you edited them;
4. on a finished free-plan song, **Licence for commercial use** (5 a month), copy the credit line and
   open the public, printable certificate;
5. read the beta terms from the footer or the sign-up step, in English or Hindi.

Sign in as `admin@example.com` at `/en/admin`: set up an authenticator app (recovery codes shown
once), then use the moderation queue (listen to held-back takes, uphold or overturn), pause or resume
accounts, and read the audit log, next to the Sprint 2 caps, plans, ledger check and failed jobs.

## Stories

| Story | Points | Status | Evidence |
| --- | --- | --- | --- |
| US-010 Hindi interface | 3 | **Done, Hindi needs native review.** Every app screen reads from `web/lib/i18n.ts` (English and Hindi, same keys). Language switch keeps you on the same page, sets a cookie for `/` and saves `locale` on the account (`PATCH /v1/me`). Dates and numbers via `Intl` with `en-IN` / `hi-IN`. Languages that have not passed G1 show as experimental (`GET /v1/languages`, `BEATMIND_G1_PASSED`). The admin console stays English (internal) | `i18n.test.ts` (same keys, no empty strings, Devanagari share), UI check on Hindi phone screens (TC-REQ-NFR-09-01), `LyricWriterTest` (TC-REQ-F-07-01) |
| US-018 AI lyric writer | 5 | **Done with template stand-in.** `POST /v1/lyrics` queues a high-priority lyrics job (no credits, 30 a day). The worker writes verse, chorus and bridge in Hindi, Hinglish or English; `llm` mode calls any OpenAI-compatible server with the topic and mood as tagged data (TM-06). Output is screened before the creator sees it. Ledger records `lyrics_generated` and `lyrics_edited` | `test_lyrics.py` (TC-REQ-F-03-01), `LyricWriterTest` |
| US-025 Free licences and certificate | 5 | **Done, wording needs counsel.** 5 commercial licences a month on Free (calendar month, India time), idempotent per song, `free_licensed` rights, ledger event. Paid songs get an ownership certificate. Public certificate page `/{locale}/certificates/BM-XXXX-XXXX-XXXX` shows an account reference, not the email, and never claims copyright; prints to PDF | `LicenceTest` (TC-REQ-F-21-01, -02, TC-REQ-F-22-01), `i18n.test.ts` copy check (TC-REQ-F-26-01) |
| US-028 Moderation | 8 | **Done, lists need native review.** Prompt, lyrics, title, rename, lyric topic and AI lyrics are checked after folding (NFKC, invisible characters, look-alike letters, leetspeak, spaced letters, Devanagari to Latin, Hinglish spelling variants). Rules: real artists and voice cloning, hate, threats, sexual content with minors. A block returns `422` with the reason and a review case; only a hash of the text is kept; an overturn lets that exact text through for that account | `TextModerationTest` with the red-team set and Unicode attack corpus (TC-REQ-F-50-01, -02, TC-REQ-F-51-01, TC-REQ-SEC-04-01), `SafetyTest` |
| US-029 Similarity screen | 8 | **Done, index empty.** Spectral-peak landmark fingerprints in PostgreSQL; a take that matches a reference is withheld, refunded, recorded and queued for review; an overturn releases it. Catches re-encodes, volume changes and excerpts, not new performances of a melody. Load references with `python -m beatmind_workers.reference add` once TD-03 is decided | `test_similarity.py` (TC-REQ-F-52-01), `SafetyTest` (TC-REQ-F-59-01) |
| US-031 Admin console | 8 | **Done.** TOTP second factor (RFC 6238, replay blocked, 10 recovery codes, lockout after 5 wrong codes) required before any admin call; users table with pause and resume; moderation queue with audio; audit log of every admin action | `AdminConsoleTest` (TC-REQ-SEC-06-01), `SecurityRulesTest` |
| US-040 G1 listening tests | 5 | **Kit done, test not run.** Blind answer sheets with random order and sides, a separate key, scoring with a Wilson interval and the PRD verdict; protocol in [g1-listening-test.md](../11-validation/g1-listening-test.md) | `test_g1.py` |

## Carry-overs from Sprint 2

- **Beta terms** `2026-11-beta`: short, plain terms in English and Hindi at `/{locale}/terms`, linked
  from the sign-up step and every footer (`web/lib/terms.ts`, copy in
  [beta-terms.md](../14-legal/beta-terms.md)). Needs counsel sign-off (L-08).
- **30-day purge**: an hourly job removes the audio files, prompt, lyrics and job data of songs
  deleted more than 30 days ago, keeps the ledger, and appends `files_purged` (`PurgeTest`).

## Tests

- API: 196 tests pass (`mvn test`), including 7 new test classes.
- Workers: 49 tests pass, ruff clean.
- Web: 20 unit tests pass, `tsc` clean, production build passes.
- Browser check (`web/e2e/ui-check.mjs`): every screen in dark and light, phone, and Hindi, including
  the blocked notice, lyric writer, licence dialog, certificate, terms, admin second factor and
  console: no serious or critical axe violations, no horizontal scroll at 390 px.

## Known gaps

1. Moderation word lists and the Hindi text were written by the team; both need native review, and
   a list-based filter will miss new slang (TODO.md).
2. The similarity index is empty until a reference catalogue is chosen (TD-03).
3. Lyrics come from templates until a lyric model is served (TD-01).
4. No phone check before free licences yet (TM-12), so one person with many emails could farm them.
5. No QR code on the admin second-factor screen (the key and an `otpauth://` link are shown).
6. Certificates print to PDF from the browser; a signed PDF file is later work.
