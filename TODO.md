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

## Sprint 1: first song end to end in staging

- [ ] **US-008 creator interviews** (Founder): hold 20 interviews with the script and log the
      answers.
- [ ] **US-009 sign-in** (Founder): create the Google OAuth client for staging and production, and
      an email-sending account for sign-in codes; add their keys as secrets.

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
