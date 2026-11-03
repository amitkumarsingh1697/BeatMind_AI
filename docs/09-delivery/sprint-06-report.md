# Sprint 6 report: international payments and stems

| Item | Value |
| --- | --- |
| Sprint | SPRINT-06, started early on 2026-11-03 |
| Goal | Paid plans outside India through a merchant of record, and separated stems |
| Points | 10 planned, 10 built locally (US-035 runs on the stub until the Paddle account exists; US-039 runs on a band-split stub until Demucs is on the GPU worker) |
| Status | Code done and tested locally. The Paddle account, prices and notifications, and Demucs on the GPU, are outside steps in [TODO.md](../../TODO.md) |

## What a developer can do now

Run `docker compose up --build`, open http://localhost:3000/en/pricing and:

1. choose **United Kingdom** (or any band country outside India): prices show in US dollars plus
   local tax, and the note says Paddle is the merchant of record;
2. choose **Creator**, sign in, then **Pay**: in stub mode the test dialog offers card outcomes
   only (no UPI) and plays Paddle's signed notifications through the real handler;
3. on a song made on a paid plan, **Stems** → **Separate into stems**: after a moment, four
   download buttons (vocals, drums, bass, everything else) and the sum check appear.

## Stories

| Story | Points | Status | Evidence |
| --- | --- | --- | --- |
| US-035 Merchant of record | 5 | **Done on the stub; Paddle account to open.** Checkout outside India makes a Paddle subscription (ADR-009): the web app opens the Paddle.js overlay with the price, the billing country and our subscription id as custom data. `POST /v1/webhooks/paddle` checks `Paddle-Signature` (HMAC-SHA256 of `timestamp:body`, refused when older than 5 minutes, either of two secrets during rotation) and handles each event once. Subscription events set the status and period; `transaction.completed` settles a period and vests songs; an approved chargeback or full refund reverses it (REQ-F-57); a partial refund keeps ownership. Paddle prices by the address, so if the person changes the country in the overlay, the billing country follows what they paid (REQ-F-41). Cancel, account deletion and reversals call Paddle's cancel API. Without Paddle keys, international checkout still answers "opens soon" | `PaddleTest` (TC-REQ-F-42-02), browser check |
| US-039 Stems | 5 | **Done on the stub; Demucs to run on the GPU.** `POST /v1/songs/{id}/takes/{n}/stems` (paid plans, and songs the person owns after the plan ends) separates the unwatermarked render of the current version once; an edit makes a new version that can be separated again. The worker runs Demucs (`htdemucs`, MDL-07) or the band-split stub, runs the sum test on the raw stems, then finishes each stem as a WAV with the AI label, watermark and C2PA (marked as a stem). Each stem downloads through a 15-minute signed link; separation and every download go in the ledger; the verify page recognises stem files by hash | `StemsTest` (TC-REQ-F-23-04), `test_stems.py` (TC-REQ-F-23-02 sum test, TC-REQ-F-23-03 SDR on a synthetic test set) |

## Fixes to Sprint 5

- Deleting a song now also purges its edited versions, their unwatermarked renders and its stems
  after 30 days (`SongPurge`); before, only the original takes were removed.
- Version 1 of a take is now recorded when the editor first opens, with the length the worker
  measured rather than the length that was asked for, so the editor's limits and default fades fit
  the real take.

## Tests

- API: 238 tests pass (`mvn test`), 7 new in 2 classes.
- Workers: 71 tests pass, ruff clean. A stems job's result is checked to serialise as JSON (a
  numpy bool crashed the worker during the browser check; fixed).
- Web: 30 unit tests pass, `tsc` clean, production build passes.
- Browser check: 67 screens, now including stems, a stem download, and plans and a test payment
  outside India; no serious or critical axe violations.
- RTM: 2 new cases (TC-REQ-F-23-04, TC-REQ-F-42-02) and 2 more written; 45 of 79 tagged.

## Known gaps

1. No real Paddle payment has run; the field the handler reads for the address country needs
   checking against a sandbox notification (TODO.md).
2. SDR is measured only for the stub on synthetic sources; Demucs needs measuring on real
   multitrack songs before stems are promised as a quality level.
3. The data export does not include stems yet; owners can download them from the song page.
4. The cancel reason is still asked but not stored.
