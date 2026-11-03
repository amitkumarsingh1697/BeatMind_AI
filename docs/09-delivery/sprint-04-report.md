# Sprint 4 report: beta hardening

| Item | Value |
| --- | --- |
| Sprint | SPRINT-04, started early on 2026-11-03 |
| Goal | Beta hardening for gate G2: beat mode, queue position, sharing, takedowns, data export and deletion, load and security tests, accessibility, investor demo |
| Points | 33 planned, 33 built locally (US-041, US-042 and the screen-reader half of US-043 as kits: they need staging, a recording and a person) |
| Status | Code done and tested locally. Staging runs, the recording, counsel and native review are outside steps in [TODO.md](../../TODO.md) |

## What a developer can do now

Run `docker compose up --build`, open http://localhost:3000/en/create and:

1. choose **Make a beat**: pick a genre, a BPM from 60 to 200, a key, and instruments to include or
   leave out; the measured tempo shows under each take;
2. watch the queue position and the wait estimate while a song is made (also read out to screen
   readers);
3. on a finished song, **Share**: make a private or public link, open it in a signed-out window,
   then revoke it and see the page stop working;
4. report a song from **Report** in the footer, as a rights holder or with a court order;
5. open **Your data** from the account menu: download a ZIP of everything, or delete the account
   after typing the email back.

As `admin@example.com` at `/en/admin`, open **Takedowns**: each report shows its clock
(acknowledge, act, or answer the appeal) and turns red when overdue. Remove the song, then, as the
owner, appeal from the song page, and restore it as the admin.

## Stories

| Story | Points | Status | Evidence |
| --- | --- | --- | --- |
| US-032 Takedowns and appeals | 5 | **Done, wording and emails need work.** `POST /v1/reports` takes reports without an account (Turnstile, 10 an hour per network, a true-and-accurate tick) and finds the song from a song link, share link or certificate ID. Clocks: a report is acknowledged within 24 hours and decided within 15 days; a court or government order is acted on within 3 hours; an appeal is answered within 72 hours. Removing a song stops downloads, licences and share links and revokes its certificates; restoring undoes all of it. Overdue counts are metrics (`beatmind.takedowns.overdue`) for the alert. Every step goes in the ledger and the admin audit log | `TakedownTest` (TC-REQ-F-54-01, TC-REQ-F-59-02), UI check of the full admin flow |
| US-011 Data export and deletion | 5 | **Done, wording needs counsel.** `GET /v1/me/export` streams a ZIP with `data.json` (account, songs with history, share links, credits, licences, lyric drafts, moderation cases, takedowns), a README and the audio in the formats the plan allows. `DELETE /v1/me` needs the email typed back; it signs out everywhere, removes personal data from the account row, deletes sessions, codes and drafts, stops queued jobs and share links, and deletes the songs (files go 30 days later with the purge). Certificates and the ledger stay, without the email | `AccountDataTest` (TC-REQ-NFR-05-01, -02) |
| US-015 Beat mode | 5 | **Done on the stub; model parameters to check.** 20 genres, 19 instruments, BPM 60 to 200, key normalised ("Am" to "A minor"). The worker sends BPM and key to ACE-Step and measures the tempo of every take (onset autocorrelation, 4% tolerance, half or double time accepted); the result is on the take and in the ledger | `BeatModeTest` (TC-REQ-F-04-01, -02), `test_beat.py` (every BPM 60 to 200 measures exactly on the stub) |
| US-024 Sharing | 5 | **Done.** Up to 10 live links a song; tokens are 32 random characters and only their hash is stored. Title and lyrics are screened again before a link is made. The public page at `/{locale}/s/{token}` shows the title, the AI-generated label and a player, never the owner or the prompt; it is hidden from search unless the link is public. Revoking, deleting or removing the song stops the link | `SharingTest` (TC-REQ-SEC-03-02, TC-REQ-F-50-03) |
| US-014 Queue position | 3 | **Done.** `GET /v1/generations/{id}` and its event stream carry `queuePosition` and `estimatedSeconds`, from the average run time of the last day and the number of active workers. The create page announces both in a polite live region | `QueueEstimateTest` (TC-UX-A11Y-15-01: over 80% of estimates within ±30% on a simulated queue) |
| US-043 Accessibility | 3 | **CI done, manual pass to run.** The CI stack job now runs `web/e2e/ui-check.mjs` (axe-core on every screen, dark, light, phone, Hindi) and fails on serious or critical violations. The NVDA and TalkBack checklist is [screen-reader-pass.md](../07-quality/screen-reader-pass.md) | CI job, TC-REQ-NFR-08-01 |
| US-042 Load and security | 5 | **Kit done, staging runs to do.** `scripts/load/k6-beta.js` runs at twice the estimated beta peak (50 at once, so 100) against REQ-NFR-01; a trial on the local stack passed every threshold. The **Security baseline** workflow runs the ZAP passive scan against staging with rules in `.zap/rules.tsv`. How to run both: [scripts/load/README.md](../../scripts/load/README.md) | Local k6 trial |
| US-041 Investor demo | 2 | **Script done, recording to do.** Five-minute script with timings, actions and voice-over: [investor-demo.md](../15-investor/investor-demo.md) | |

## Tests

- API: 212 tests pass (`mvn test`), including 5 new test classes.
- Workers: 60 tests pass, ruff clean.
- Web: 22 unit tests pass, `tsc` clean, production build passes.
- Browser check: 51 screens, including beat mode, sharing, the shared page on a Hindi phone, the
  report form, Your data, the delete dialog and the admin takedown flow; no serious or critical axe
  violations. The light-theme green (`--leaf-700`) was darkened to `#0a6b42` to reach 4.5:1.
- Load: a 3-minute k6 trial on the local stack (stub GPU) passed every threshold; it says nothing
  about real latency until it runs on staging with the model.

## Known gaps

1. Owners and reporters are not emailed yet: a reporter gets a reference number on screen, and an
   owner sees a removal on the song page. The grievance officer flow needs email (TODO.md).
2. The takedown, export and deletion text needs counsel review, and the new Hindi text needs native
   review.
3. ACE-Step's `bpm` and `key_scale` inputs are wired but not yet checked on the GPU, and the key of
   a take is not measured yet (only the tempo).
4. The beta peak behind the load test is an estimate; re-run it when the first beta week shows the
   real number.
5. Takedown evidence is a link, not an upload.
