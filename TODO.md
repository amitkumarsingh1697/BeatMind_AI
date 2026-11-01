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

## Sprint 3: safe and multilingual

- [ ] **US-040 G1 listening tests** (Founder): recruit at least 30 native listeners per language
      for Hindi, Hinglish and English, run the tests on GPU-made songs, and record which languages
      pass.
- [ ] Founder: hire a native Hindi copywriter for the Hindi interface (US-010).

## Sprint 4: beta hardening

- [ ] **US-042** (Any developer): run the load test and security scan against staging once it is
      live.
- [ ] **US-041** (Founder): record the investor demo.

## Sprint 5 and 6: payments

- [ ] Founder: register the Indian company and open the Razorpay account (Q-F01), then add the
      Razorpay keys as secrets (US-034).
- [ ] Founder: sign up with a merchant of record for international payments (US-035).
- [ ] Founder: have counsel review the terms and the certificate wording before paid launch.
