# Sprint 2 report: songs are labelled, provable and downloadable

| Item | Value |
| --- | --- |
| Sprint | SPRINT-02, started early on 2026-11-02 (planned 30 November to 11 December) |
| Goal | Songs are labelled, provable and downloadable |
| Extra scope | Visual design for every screen (founder, 2026-11-02) |
| Points | 39 planned, 39 built locally |
| Status | Code done and tested locally. Real signing keys, the AudioSeal watermark and staging are outside steps in [TODO.md](../../TODO.md) |

## What a developer can do now

Run `docker compose up --build`, open http://localhost:3000/en/create and:

1. sign in with an email code and pass the 18+ step;
2. describe a vibe, or switch to **Use my lyrics** and paste Hindi, Hinglish or English lyrics (up to
   3,000 characters, counted as people see them), pick a voice and language;
3. watch the credit meter (20 credits a day on Free; a song uses 2) and the live status;
4. open the song: play both takes with the new player, read the lyrics, see who owns it and its
   verified history;
5. download the take: Free gets MP3 at 192 kbps, Creator and Pro also get MP3 at 320 kbps and WAV;
   each file is watermarked, tagged as AI-generated and carries a signed C2PA manifest;
6. find songs in the **Library**, in either script ("raat ka safar" finds "रात का सफ़र"), rename or
   delete them.

Sign in as `admin@example.com` for `/en/admin/jobs`: set the free daily cap, put an account on
Creator or Pro (payments come later), verify every ledger chain and retry failed jobs.

`scripts/dev/first-song-smoke.sh` now also checks the library, a signed MP3 download (AI label and
C2PA present) and the ledger chain. `web/e2e/ui-check.mjs` walks every screen in a browser.

## Stories

| Story | Points | Acceptance criteria | Status | Evidence |
| --- | --- | --- | --- | --- |
| US-017 Custom lyrics | 5 | Grapheme counting; NFC; language tag stored; custom mode works | **Done.** Lyrics mode with style, title and voice. Text stored in NFC; 3,000-character limit counted with ICU, which now matches the browser for Devanagari conjuncts (fixes Sprint 1 gap 3). Titles stored in the original script plus a Hinglish-style Latin form and a BCP 47 tag (`hi`, `hi-Latn`, `en`) | `LyricsTest` (TC-REQ-F-02-01, -02, TC-REQ-F-08-01); `TextRules` |
| US-019 Ledger | 5 | Chain verification passes; tamper test fails as expected | **Done.** `provenance_event`, one SHA-256 chain per song over canonical JSON. Prompts, lyrics, debits, takes, refunds, exports, renames and deletes are recorded, with human, AI or system as the actor. Text is stored as hashes. The table rejects UPDATE and DELETE. Admin and song-page verification | `ProvenanceLedgerTest` (TC-REQ-F-31-01, -02) |
| US-020 Labels, watermark, C2PA | 8 | AI label metadata, valid C2PA manifest, detectable watermark on every export | **Done with development stand-ins.** The worker turns each take into MP3 192, MP3 320 and WAV 48 kHz 24-bit, each with "AI-generated. Made with BeatMind AI." tags, a C2PA manifest (`trainedAlgorithmicMedia`) and a 32-bit spread-spectrum watermark that survives MP3 encoding at 128 kbps and up. Signing uses a throwaway certificate until the real one exists; AudioSeal replaces the development watermark on the GPU box (TODO.md) | `test_finishing.py` (TC-REQ-F-30-01) |
| US-022 Library | 5 | List, search (Unicode-aware), rename, delete | **Done.** API-003 with paging; search in any script plus a "sounds like" Latin key; rename and delete are ledger events | `LibraryTest` |
| US-023 Player | 5 | Keyboard operable; ARIA; lyrics shown; label visible | **Done.** Native controls, spoken position, mute and volume, no autoplay, one take at a time, seeking via HTTP ranges; lyrics with `lang`; AI label in the player | axe-core: 0 violations on all screens; `LibraryTest` (range request) |
| US-026 Downloads | 3 | Signed URLs 15 min; formats by plan per entitlement matrix | **Done.** API-007 issues HMAC-signed links that expire after 15 minutes and work without a session; Free gets MP3 192, paid plans MP3 320 and WAV | `DownloadsTest` (TC-REQ-F-20-01, TC-REQ-F-23-01) |
| US-027 Refunds | 3 | Automatic refund with ledger entry | **Done.** Append-only `credit_ledger` (1 credit = 1 take). Failed, timed-out and withheld takes are refunded once, recorded in both ledgers and shown to the user | `RefundsTest` (TC-REQ-F-56-01) |
| US-030 Caps | 5 | Turnstile; daily caps; concurrency limits; velocity rules | **Done.** Turnstile on song requests when a key is set; free daily cap set by admins (default 20 credits) with per-account override; 1, 2 or 4 songs in progress by plan; 6 requests per account per 10 minutes and 60 per network per hour | `CapsTest` (TC-REQ-F-55-01, TC-REQ-SEC-09-01) |

## Visual design (added scope)

The product now has a look: marigold for action, rani pink for sound, iris for the AI label, Baloo 2
and Mukta type (both draw Devanagari), light and dark themes from the device setting. Every existing
page was restyled (waitlist, create, admin) and the new ones built on the same components. The
rules are in [visual-design.md](../08-ux/visual-design.md) and on the live style guide at
`/en/design`.

## Tests

| Part | Tests | Result |
| --- | --- | --- |
| API (JUnit, PostgreSQL 16) | 96 (67 before this sprint) | Pass |
| Workers (pytest, PostgreSQL 16, ffmpeg) | 31 (18 before) | Pass, lint clean |
| Web (node test, type check, build) | 12 (7 before) | Pass |
| End to end | Smoke script through the web forwarder: sign in, song, live status, takes, library, signed MP3 download with label and C2PA, ledger chain | Pass locally |
| Accessibility | `web/e2e/ui-check.mjs`: axe-core (WCAG 2.2 A and AA rules) on 21 screen states, light and dark, desktop and 390 px phone | 0 violations |

Not checked here: the Docker images (Docker is not available where this sprint was built; CI
builds them and runs the smoke test). The stack was run directly instead, with a local S3 stand-in.

## Known gaps and follow-ups

1. **Real music needs a GPU** (unchanged). Takes are 2-second tones until ACE-Step runs.
2. **Development signing and watermark.** C2PA manifests verify but are signed by a throwaway
   certificate; the watermark is a development stand-in for AudioSeal and does not survive cropping
   or pitch changes. Both are in TODO.md.
3. **Hourly signed ledger digest** (REQ-SEC-07, TM-08) needs the KMS key; the chain and the
   append-only rule are in place.
4. **Deleted songs' files** are hidden at once but not yet purged after 30 days (TDD 5.2); a purge
   job is planned with the account deletion work.
5. **Lyrics of vibe-mode songs** are written by the model; showing them needs ACE-Step to return
   them (UX-A11Y-02 is met for custom lyrics).
6. **Paid allowances** (600 and 2,000 credits a month) are placeholders until pricing fixes them.
7. **Hindi interface** for the app screens waits for US-010 (Sprint 3).
8. **The UI check is not in CI** yet: it needs Playwright in the pipeline and a running stack.
9. **Terms text** for version `2026-11-beta` is still missing (Sprint 1 gap 2).

## Outside steps added to TODO.md

Production C2PA signing certificate and KMS key, AudioSeal on the GPU box, the download-link
secret, the hourly ledger digest key and the Turnstile key for song requests.
