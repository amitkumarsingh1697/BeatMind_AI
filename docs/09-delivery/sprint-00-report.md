# Sprint 0 report: foundations and risk spikes

| Item | Value |
| --- | --- |
| Sprint | SPRINT-00, started early on 2026-10-29 (planned 2 to 13 November) |
| Goal | Foundations and risk spikes: can the model do Hindi cheaply? |
| Points | 22 planned |
| Status | Code and kits done. US-005 and US-006 need a rented GPU and three listeners to produce their results. |

## Stories

| Story | Points | Acceptance criteria | Status | Evidence |
| --- | --- | --- | --- | --- |
| US-001 Monorepo with CI | 5 | CI runs unit tests, scans and RTM check on every pull request; main is protected | **Done except branch protection** (a GitHub setting only the owner can turn on) | `.github/workflows/ci.yml`; `scripts/check_rtm.py`; `.gitleaks.toml` |
| US-002 Local stack | 3 | `docker compose up` starts web, API, Postgres, Valkey, object store and stub worker; README documents it | **Done.** SeaweedFS (Apache-2.0) stands in for MinIO, because a maintained MinIO community image could not be confirmed. CI starts the stack and checks that a queued job is processed | `docker-compose.yml`; CI job "Local stack smoke test" |
| US-003 Environments in OpenTofu | 5 | Plan and apply succeed from CI; secrets from vault | **Written and validated, not applied.** Needs Cloudflare and Oracle accounts and GitHub environment secrets (see `infra/README.md`) | `infra/`; `.github/workflows/infra.yml` |
| US-005 ACE-Step benchmark | 3 | Report with seconds per take, VRAM and cost per take for 3 GPU types; licence file archived | **Kit ready; licence archived.** Needs a rented GPU (about 3 hours in total, within the ₹3,000 monthly GPU budget) | `workers/bench/`; `docs/06-ai/licences/ace-step-1.5-LICENSE-2026-10-29.txt` |
| US-006 Hindi and Hinglish spike | 3 | 20 prompts per language scored by 3 native speakers; go or fallback decision | **Kit ready.** 20 Hindi and 20 Hinglish prompts with original lyrics; blind scoring sheets and scorer. Needs the GPU run, then 3 listeners | `workers/spikes/hindi/` |
| US-007 Waitlist page | 3 | Page live in English and Hindi; sign-ups stored with consent; price variant recorded | **Built and tested, not live.** Needs a domain and hosting (US-003) | `web/`; `api/` waitlist and pricing modules |

## What was built

- **Core API** (`api/`): Spring Boot 4.1 modular monolith with three modules, checked by Spring Modulith:
  - **pricing** (API-008, REQ-F-40): price bands by country, rupee prices for India, and the two EXP-02 price variants;
  - **waitlist**: sign-up with consent, an 18+ confirmation and an optional Turnstile bot check, plus anonymous visit counts per variant;
  - **generation**: the PostgreSQL job queue (ARCH-03).
  - It has 27 JUnit tests, one tagged TC-REQ-F-40-01.
- **Workers** (`workers/`): queue claiming with `SKIP LOCKED`, retries with backoff, a dead-letter state, and recovery of jobs from crashed workers. The CPU stub runner writes audio to S3-compatible storage. There are 10 pytest tests.
- **Web** (`web/`):
  - a waitlist page in English and Hindi that shows local prices and assigns the EXP-02 variant;
  - a server-side forwarder so the browser talks to one origin;
  - it fits at phone width, and was checked in a headless browser.
- **Docs updated**:
  - DPIA and privacy policy now cover waitlist data;
  - TDD and ADR-002 now name Spring Boot 4.1;
  - the RTM marks TC-REQ-F-40-01 as written.

## Needs the founder

1. **Turn on branch protection for `main`**. In GitHub, go to Settings, then Branches, and add a rule. Require pull requests and the CI checks to pass.
2. **Rent a GPU for about 3 hours** and run the benchmark, following `workers/bench/README.md`. Any developer can do this.
3. **Confirm the spike pass marks**, then find 3 native Hindi listeners.
   - Proposed pass marks: mean pronunciation at least 3.5 out of 5, and at least 60% of takes usable.
4. **Create the Cloudflare and Oracle accounts** and add the secrets listed in `infra/README.md`. Then the waitlist can go live.
5. **Before the waitlist goes live:**
   - get a native-speaker review of the Hindi copy;
   - set a contact email (`NEXT_PUBLIC_CONTACT_EMAIL`) and a Turnstile key;
   - have counsel confirm the waitlist retention period.
