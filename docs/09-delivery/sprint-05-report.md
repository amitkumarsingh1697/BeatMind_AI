# Sprint 5 report: payments, ownership, editor, verify

| Item | Value |
| --- | --- |
| Sprint | SPRINT-05, started early on 2026-11-03 |
| Goal | Paid plans in India with UPI and cards, ownership that follows the payment, the song editor and the public verify page |
| Points | 35 planned, 35 built locally (US-034 and US-016 run on stubs until the Razorpay account and the Lyria endpoint exist) |
| Status | Code done and tested locally. Razorpay keys, the webhook, Lyria, counsel and native review are outside steps in [TODO.md](../../TODO.md) |

## What a developer can do now

Run `docker compose up --build`, open http://localhost:3000/en/pricing and:

1. pick a billing country: India shows rupee prices including GST; other countries show their band
   and say paid plans open soon;
2. choose **Creator**, then **Pay**: in stub mode a test dialog plays the payment (UPI or card
   succeeds, a card from another country is refunded, or UPI stays pending) through the real signed
   webhook handler;
3. make a song: it says **You own this song** once the period's payment has settled;
4. on the song page, **Edit this take**: trim, fade, extend the end or remake a section. Each edit is
   a new version with its own player; extend and remake show the seam check; **Use this version**
   goes back to any earlier one;
5. open **Your data**: the plan panel shows the renewal date and the payments, and **Cancel plan**
   takes three steps (why, what happens, confirm). The plan runs to the end of the period and owned
   songs stay downloadable;
6. signed out, open **Verify a song** in the footer: paste a certificate ID or upload a file.

As `admin@example.com`, `GET /v1/admin/fallback` shows today's Lyria spend against the cap.

## Stories

| Story | Points | Status | Evidence |
| --- | --- | --- | --- |
| US-033 Prices by billing country | 3 | **Done.** Checkout takes the billing country the person confirms and prices from its band (`PricingService`); the country is saved on the account. A payment whose card country differs from the billing country, or an international card, is refunded and the subscription cancelled | `BillingTest` (TC-REQ-F-41-01) |
| US-034 Razorpay | 8 | **Done on the stub; account and keys to add.** New `billing` module. Checkout creates a Razorpay subscription (stub, test or live mode). `POST /v1/webhooks/razorpay` checks the HMAC-SHA256 signature on the raw body, processes each event ID once (`payment_webhook`), and handles authenticated, activated, charged, pending, halted, cancelled, completed, dispute lost and refunds. The web forwarder now passes bytes and the two Razorpay headers through | `BillingTest` (TC-REQ-F-42-01), browser check |
| US-036 Ownership | 5 | **Done, wording needs counsel.** A song made on a paid plan is `vesting` until the payment for its period settles, then `user_owned` (ledger `ownership_vested`). A chargeback or full refund reverses the period: its songs go back to BeatMind, their ownership certificates are revoked, and the plan stops if the period is current. A plan that ends unpaid leaves vesting songs with BeatMind. Licences wait while a song is vesting | `BillingTest` (TC-REQ-F-57-01) |
| US-037 Cancel | 3 | **Done.** Three steps from Your data; the plan ends at the period end (a scheduled job also ends it if Razorpay's event is late). Owned songs keep MP3 and WAV after the plan ends; songs made later follow the free plan | `BillingTest` (TC-REQ-F-58-01), browser check |
| US-038 Editor | 8 | **Done on the stub; ACE-Step edit modes to wire.** `POST /v1/songs/{id}/edits` makes a new version (`take_version`); trim and fade are free, extend and remake cost one credit and are refunded on failure. Every edit starts from the unwatermarked render, so marks are never stacked. Extend and remake join new audio with equal-power crossfades and level matching; the seam check compares the largest step at each join with the take's typical step (limit 3×). Versions can be played and restored, and every step is in the ledger | `EditTest` (TC-REQ-F-10-01, TC-REQ-F-11-01), `test_editing.py` (TC-REQ-F-10-02) |
| US-021 Verify | 5 | **Done.** `POST /v1/verify` takes a certificate ID or a file (up to 30 MB, Turnstile, 20 checks an hour per network). A file is matched by SHA-256 first; otherwise a worker reads our watermark and C2PA credentials and the upload is deleted. The answer is coarse: made with BeatMind or not, the day, AI-generated, who wrote the lyrics, edited, removed. Never the title, owner or prompt | `VerifyTest` (TC-REQ-F-32-01), browser check |
| US-016 Lyria fallback | 3 | **Done on the stub; endpoint to set.** Paid songs go to provider `lyria` when the estimated wait is over 2 minutes or the language is routed there, under a daily cap (US$10, India time) checked under a lock. Self-hosted workers never claim Lyria jobs. Estimated and actual cost are stored per job; metrics `beatmind.fallback.routed`, `.capped` and `.spend.today.usd` | `FallbackTest` |

## Tests

- API: 231 tests pass (`mvn test`), 19 new in 4 classes.
- Workers: 66 tests pass, ruff clean.
- Web: 27 unit tests pass, `tsc` clean, production build passes.
- Browser check: 63 screens, now including plans, the test payment, an owned song, the editor with
  three versions, the cancel steps, the verify page by certificate and by file, and plans and verify
  in Hindi on a phone; no serious or critical axe violations.
- RTM: 8 more cases written (41 of 77 tagged).

## Known gaps

1. No real payment has run yet: stub mode exercises the same handler, but test mode needs the
   Razorpay account, plans and webhook (TODO.md).
2. Extend and remake use the stub renderer; the seam limit needs checking on ACE-Step output.
3. The cancel reason is asked but not stored yet.
4. Plan allowances on the cards (600 and 2,000 credits) are placeholders from `application.yml`.
5. International payments wait for the merchant of record (US-035, Sprint 6).
6. Ownership, chargeback and verify wording needs counsel review; the new Hindi text needs native
   review.
