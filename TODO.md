# Release TODO: non-code tasks

Tasks that are not code: accounts, keys, rented hardware, people, legal and business steps. They
are parked here so sprints can keep building the frontend, backend, database and tests. We do them
in one go when a real release is near (founder, 2026-11-01).

**Rule for every sprint:** when a story needs something outside the code, build and test the code
with a stub or local setting, then add the outside step here under the sprint it came from. Tick
it off when it is done.

Each task says who can do it: **Founder** (needs Amit's accounts, money or signature) or **Any
developer**.

## Sprint 0: foundations and risk spikes

### GPU runs (Any developer, rented GPU)

- [ ] **US-005 model benchmark** (about 3 hours, within the ₹3,000 monthly GPU budget)
  1. Rent each of 3 GPU types, for example an RTX 4090, an L4 and an RTX 3090 or A10.
  2. Follow "Run it" in `workers/bench/README.md` to get seconds per take, VRAM and cost per take.
  3. Copy each `summary.md` and `results.csv` into `docs/06-ai/benchmarks/<gpu>/` and pick the
     GPU type for production.
- [ ] **US-006 Hindi and Hinglish spike** (the quick 5-song CPU version is done)
  1. Founder: confirm the pass marks. Proposed: mean pronunciation at least 3.5 out of 5, and at
     least 60% of takes usable.
  2. On the GPU, run `workers/spikes/hindi/generate.py` for all 20 Hindi and 20 Hinglish prompts.
  3. Founder: find 3 native Hindi listeners and give each the audio, lyrics sheet and own sheet.
  4. Score the sheets and record the go or fallback decision in the decision log.
- [ ] **Song quality remake loop** (repeat until the songs are good enough)
  1. Follow `workers/spikes/song-remake/README.md` and run `python3 remake.py --out remake --takes 6`.
  2. Listen, measure pitch range, dynamics and rap timing, adjust the caption or settings, and
     run again.
  3. If the takes are still flat after the settings fixes, start the "plan, then render" build
     from the song-quality diagnosis.

### Cloud environments, US-003 (Founder)

- [ ] Create a Cloudflare account, add the product domain, and make an API token limited to that
      zone's DNS and to R2.
- [ ] Create an Oracle Cloud account in Mumbai (`ap-mumbai-1`) and an API key for a deploy user.
- [ ] In R2, create the private bucket `beatmind-tofu-state` and an access key for it.
- [ ] In GitHub, create the `staging` and `production` environments (production with a required
      reviewer) and add the secrets listed in `infra/README.md`.
- [ ] Run the Infrastructure workflow: `plan`, read it, then `apply`.

### Waitlist page goes live, US-007

- [ ] Founder: buy the domain, after a trademark search for the name (C-01).
- [ ] Founder: get a native speaker to review the Hindi copy on the page.
- [ ] Founder: choose the contact email and set `NEXT_PUBLIC_CONTACT_EMAIL`.
- [ ] Founder: create a Cloudflare Turnstile key and set `TURNSTILE_SECRET` (API) and
      `NEXT_PUBLIC_TURNSTILE_SITE_KEY` (web).
- [ ] Founder: have counsel confirm how long waitlist sign-ups are kept.
- [ ] Any developer: deploy web and API to staging, then production, once the environments exist.

### Done

- [x] Branch protection on `main`, via the GitHub ruleset (2026-10-31).

## Sprint 1: first song end to end in staging (code runs locally; these steps make it staging)

- [ ] **US-008 creator interviews** (Founder): hold 20 interviews with the script and log the
      answers.
- [ ] **US-009 sign-in** (Founder): create the Google OAuth client for staging and production, and
      an email-sending account for sign-in codes; add their keys as secrets.
  1. Google Cloud console: OAuth client of type "Web application", redirect URI
     `https://<domain>/v1/auth/google/callback`. Set `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
     and `GOOGLE_REDIRECT_URI` on the API.
  2. Pick the email provider (INT-12, free tier). Any developer then adds its `EmailSender`
     (one small class next to `LogEmailSender`) and its key.
  3. Any developer: in staging and production set `BEATMIND_AUTH_PEPPER` (a random secret of at
     least 32 characters), `BEATMIND_ADMIN_EMAILS`, `BEATMIND_WEB_ORIGIN`, and keep
     `BEATMIND_AUTH_DEV_CODES` off and `BEATMIND_AUTH_COOKIE_SECURE` on (the defaults).
- [ ] **Beta terms and privacy text** (Founder, counsel): write the beta terms and privacy policy
      that the sign-up checkbox accepts as version `2026-11-beta`, and link them from the page.
      Needed before anyone outside the team signs in.
- [ ] **Real songs on a GPU, US-012** (Any developer, rented GPU)
  1. Start the ACE-Step 1.5 server on the GPU box (`uv run acestep-api`, port 8001; see
     `workers/bench/README.md`) behind an API key or a private network.
  2. Run the worker with `BEATMIND_RUNNER=acestep`, `ACESTEP_URL` and `ACESTEP_API_KEY`, and set
     `GPU_USD_PER_HOUR` on the API to the rented price so cost per song is right.
  3. In staging, check the acceptance criterion: p95 time to playable under 60 seconds on the
     Grafana dashboard ("Time to playable" panel).
- [ ] **Monitoring for staging, US-004** (Founder account, then any developer)
  1. Choose the hosted stack: Grafana Cloud free tier, or self-hosted Prometheus, Grafana and
     Tempo on the Oracle VM.
  2. Point Prometheus at `/actuator/prometheus` on the API, load `infra/observability/alerts.yml`
     and import `infra/observability/grafana/dashboards/beatmind-pipeline.json`.
  3. Set `TRACING_EXPORT_ENABLED=true` and `OTEL_TRACES_ENDPOINT` on the API, and route alerts to
     the on-call email or chat.
  4. Keep `/actuator/prometheus` off the public internet (only the web app's `/v1` routes are
     public).
- [ ] **GPU use per card** (Any developer, with the GPU box): add the NVIDIA DCGM exporter so the
      dashboard shows GPU memory and utilisation, not only model busy time.

## Sprint 2: labelled, provable and downloadable (code runs locally; these steps make it real)

- [ ] **C2PA signing certificate, US-020** (Founder, then any developer)
  1. Buy a document-signing certificate from a CA on the C2PA trust list (cost unknown; INT-07).
  2. Keep the private key in a managed KMS (REQ-SEC-07); give the worker a signing identity and set
     `C2PA_SIGN_CERT` and `C2PA_SIGN_KEY` (or replace them with a KMS signer). Until then files are
     signed with a throwaway development certificate.
- [ ] **AudioSeal watermark, US-020** (Any developer, rented GPU): run AudioSeal (INT-06) on the GPU
      box in place of `workers/beatmind_workers/watermark.py`, set the payload and robustness
      targets (TD-04), and keep `BEATMIND_WATERMARK_KEY` secret in staging and production.
- [ ] **Hourly signed ledger digest, US-019** (Any developer, after the KMS key): sign the latest
      hash of every song chain each hour and publish it (TM-08, ADR-010).
- [ ] **Secrets for staging and production** (Any developer): `BEATMIND_DOWNLOAD_SECRET` (random,
      at least 32 characters) on the API; `TURNSTILE_SECRET` now also protects song requests, so
      set `NEXT_PUBLIC_TURNSTILE_SITE_KEY` on the web build.
- [ ] **Paid allowances** (Founder): fix the monthly credits for Creator and Pro (placeholders 600
      and 2,000) and the free daily cap (20 credits, admins can change it at run time).
- [ ] **Five quick UX tests** (Founder): first-click on Create, Hindi copy, licence panel, price
      page and a screen-reader walkthrough (UX spec section 9), on the new design.

## Sprint 3: safe and multilingual (code runs locally; these steps make it real)

- [ ] **US-040 G1 listening tests** (Founder, then any developer): follow
      `docs/11-validation/g1-listening-test.md`. Recruit at least 30 native listeners per language
      for Hindi, Hinglish and English, make the clips on the GPU, run `g1 plan` and `g1 analyse`, and
      set `BEATMIND_G1_PASSED` for the languages that pass. Until then every language shows as
      experimental.
- [ ] **Native Hindi review** (Founder): a native Hindi copywriter reviews the app text in
      `web/lib/i18n.ts`, the beta terms in `web/lib/terms.ts`, and the Hindi words in the moderation
      lists (`api/src/main/resources/moderation/lexicon.tsv`). The lists also need a native speaker
      who knows current slurs and slang, and a re-run of the red-team set afterwards (US-010, US-028).
- [ ] **Counsel sign-off on the beta terms** (Founder): version `2026-11-beta`, English and Hindi
      (`docs/14-legal/beta-terms.md`, point L-08), before the closed beta opens. If the wording
      changes in a way that matters, bump `TERMS_VERSION` so everyone accepts again.
- [ ] **Similarity reference catalogue, TD-03** (Founder decides, any developer loads): choose which
      recordings to screen against (for example a licensed Bollywood fingerprint set, or a
      commercial ACR service), then load them with `python -m beatmind_workers.reference add`. The
      index is empty until then, so the screen passes every take. A melody or cover-song model is
      a later step.
- [ ] **Lyric model, TD-01** (Any developer, rented GPU): pick an open-weight lyric model after a
      Hindi lyric test, serve it with an OpenAI-compatible server (for example vLLM), and set
      `BEATMIND_LYRICS_WRITER=llm`, `LYRICS_LLM_URL`, `LYRICS_LLM_MODEL` (and `LYRICS_LLM_API_KEY`
      if needed) on the worker. Until then lyrics come from fixed templates.
- [ ] **Phone check before free licences, TM-12** (Founder picks a provider, any developer builds):
      add a one-time SMS code before the first commercial licence, so one person cannot farm
      licences with many emails.
- [ ] **Admin accounts** (Founder): list the admin emails in `BEATMIND_ADMIN_EMAILS` for staging and
      production; each admin sets up an authenticator app on first visit to `/en/admin` and stores
      the recovery codes in the team password manager.
- [ ] **Later improvements** (Any developer): a QR code on the admin second-factor screen, passkeys
      for admins, a support role with read-only access, and certificates as signed PDF files (today
      the certificate page prints to PDF).

## Sprint 4: beta hardening (code runs locally; these steps make it real)

- [ ] **US-042 load test** (Any developer, once staging is live): make the test accounts, raise the
      per-network limit for the test window and run `scripts/load/k6-beta.js` at twice the beta
      peak with the real GPU workers, as in `scripts/load/README.md`. Record the result and set
      TC-REQ-NFR-01-01 in the RTM. Re-run when the first beta week shows the real peak.
- [ ] **US-042 security baseline** (Any developer): run the **Security baseline** workflow against
      staging from the Actions tab, triage every warning (fix it, or mark it `IGNORE` in
      `.zap/rules.tsv` with a reason) and log each one in `docs/registers/review-findings.csv`.
- [ ] **US-043 screen-reader pass** (Any developer, ideally with a screen-reader user): run the 12
      flows in `docs/07-quality/screen-reader-pass.md` with NVDA and TalkBack on staging and record
      the results there.
- [ ] **US-041 investor demo** (Founder): fill in the opening line, then record the five-minute demo
      in `docs/15-investor/investor-demo.md` on staging with the real model, and link it from the
      diligence pack.
- [ ] **Counsel review** (Founder): the report form, the takedown clocks and removal text
      (`report`, `takedown` and `rights.disabled` in `web/lib/i18n.ts`), and the data export and
      account deletion text (`settings`). Check the 3-hour and 15-day clocks against the current IT
      Rules, and name the grievance officer.
- [ ] **Takedown emails** (Any developer, after the email provider is chosen): send the reporter an
      acknowledgement with the reference, tell the owner when a song is removed or restored, and
      email the grievance officer when a clock is close to running out. Until then the overdue
      alert (`beatmind.takedowns.overdue` above 0) must page someone.
- [ ] **Native Hindi review** (Founder): the Sprint 4 text in `web/lib/i18n.ts` (beat mode, share,
      report, takedown, settings and the shared page).
- [ ] **ACE-Step beat settings** (Any developer, rented GPU): check that `bpm` and `key_scale` reach
      the model and that measured tempos land within tolerance on real takes; add a key check.

## Sprint 5 and 6: payments

- [ ] Founder: register the Indian company and open the Razorpay account (Q-F01), then add the
      Razorpay keys as secrets (US-034).
- [ ] Founder: sign up with a merchant of record for international payments (US-035).
- [ ] Founder: have counsel review the terms and the certificate wording before paid launch.
