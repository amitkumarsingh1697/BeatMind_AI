# Sprint 1 report: first song end to end

| Item | Value |
| --- | --- |
| Sprint | SPRINT-01, started early on 2026-11-01 (planned 16 to 27 November) |
| Goal | First song end to end in staging |
| Adjusted goal | First song end to end **locally**, because staging accounts are deferred to [TODO.md](../../TODO.md) (founder, 2026-11-01) |
| Points | 26 planned; 23 built (US-004, US-009, US-012, US-013); US-008 interviews (3) are founder work in TODO.md |
| Status | Code done and tested locally. Songs are test tones until a GPU runs ACE-Step (TODO.md). |

## What a developer can do now

Run `docker compose up --build`, open http://localhost:3000/en/create and:

1. sign in with an email code (locally the code is filled in for you; no email is sent);
2. enter a date of birth (18+) and accept the terms;
3. describe a song, pick Hindi, Hinglish or English, and press "Make two takes";
4. watch the status change live, then play both takes.

Sign in as `admin@example.com` to see the failed-jobs page at `/en/admin/jobs`. Add
`--profile observability` (with `TRACING_EXPORT_ENABLED=true`) for Grafana on port 3001,
Prometheus on 9090 and Jaeger on 16686.

`scripts/dev/first-song-smoke.sh` runs the same flow with curl, and CI now runs it against the
Docker Compose stack on every pull request.

## Stories

| Story | Points | Acceptance criteria | Status | Evidence |
| --- | --- | --- | --- | --- |
| US-009 Sign-in | 5 | Sign-in works; sessions secure per REQ-SEC-01; 18+ check at sign-up | **Done locally.** Email code and Google sign-in (PKCE, state bound to the browser, nonce). Codes last 10 minutes with 5 attempts; 5 codes per email and 30 per IP an hour. Sessions are random tokens in an HttpOnly, SameSite=Lax cookie, stored only as hashes, 14 days at most and 3 days idle. Under-18 accounts are deleted at once and only the birth month is kept. Google sign-in and real email need the founder's keys (TODO.md) | `api/.../identity/`; `EmailSignInTest` (TC-REQ-SEC-01-01), `ProfileTest` (TC-REQ-F-53-01), `GoogleSignInTest` |
| US-012 Describe a vibe, get two takes | 8 | API-001 and API-002 with SSE; two takes; p95 under 60 s in staging | **Done locally.** `POST /v1/generations`, `GET /v1/generations/{id}`, live `.../events` stream, take audio, recent songs. Prompts are counted in user-perceived characters (500 max), and each user may have 2 songs in progress. The p95 check needs staging and a GPU (TODO.md) | `api/.../generation/`; `GenerationApiTest`; `web/app/[locale]/create/` |
| US-013 Durable queue | 5 | SKIP LOCKED claims; retry with backoff; dead-letter visible in admin | **Done.** Claims, retries and dead-letter came in Sprint 0. Sprint 1 adds the admin list of dead jobs and an audited retry, plus start and finish times on every job | `AdminJobsController`; `admin_audit` table; `web/app/[locale]/admin/jobs/` |
| US-004 Observability | 5 | Dashboards for latency, errors, queue depth, GPU use, cost per song | **Done locally.** Prometheus metrics for API latency and errors, queue depth, oldest waiting job, time to playable, worker busy time and cost per song against its ceiling. OpenTelemetry traces (checked exporting to an OTLP collector), JSON logs, a Grafana dashboard and alert rules. A hosted stack for staging is in TODO.md | `GenerationMetrics` (TC-REQ-NFR-07-01); `infra/observability/` |
| US-008 Creator interviews | 3 | 20 interviews with notes and synthesis | **Founder task, deferred** (TODO.md) | |

## Also built

- **ACE-Step worker runner** (`workers/beatmind_workers/acestep.py`): set `BEATMIND_RUNNER=acestep`
  and `ACESTEP_URL` to make real songs on a GPU box. It uses the same request settings as the
  Sprint 0 benchmark kit. It is tested against a fake ACE-Step server, not a real GPU.
- **Stub runner** now returns two different takes per song, so the whole two-take flow runs
  without a GPU.
- The web forwarder now passes the session cookie, streams live status and audio, and uses an
  allowlist of API routes with tests.
- Daily clean-up of old sign-in codes and expired sessions; the DPIA records the new data.

## Tests

| Part | Tests | Result |
| --- | --- | --- |
| API (JUnit, PostgreSQL 16) | 67 (27 before this sprint) | Pass |
| Workers (pytest, PostgreSQL 16) | 18 (10 before) | Pass |
| Web (node test, type check, build) | 7 | Pass |
| End to end | Sign in, make a song and play both takes in a real browser at phone width; smoke script through the web forwarder | Pass locally |

Not checked here: the Docker images and the Grafana and Prometheus containers, because Docker was
not available where this sprint was built. CI builds the images and runs the smoke test; the
dashboard and alert files were checked only as valid YAML and JSON.

## Known gaps and follow-ups

1. **Real music needs a GPU.** Until ACE-Step runs on a rented GPU, takes are 2-second tones.
2. **Terms and privacy text.** The profile step records acceptance of version `2026-11-beta`,
   which does not exist yet. Counsel must write it before anyone outside the team signs in.
3. **Character counting.** Java 21 splits a few Devanagari conjuncts that browsers count as one
   character, so the API can count slightly more than the page's counter near the 500 limit. A
   Java upgrade or a shared ICU library would fix it.
4. **Live status polls the database** once a second per open page. That is fine for the beta;
   switch to PostgreSQL LISTEN/NOTIFY if many pages are open at once.
5. **OpenAPI specification** (TDD section 7.1) is not generated yet; planned with Sprint 2.
6. **Hindi interface** for the new pages waits for US-010 (Sprint 3); they are English only.

## Outside steps added to TODO.md

Google OAuth client and email provider (already listed), the production sign-in settings, a GPU
box for ACE-Step, the p95 check in staging, a hosted monitoring stack with alert routing, and the
beta terms and privacy text.

## UI timeline (answer to the founder's question)

Sprint 1 gives developers a working "make a song" page locally. Sprint 2 brings the creator UI the
plan describes: custom lyrics, a library, an accessible player and downloads. So from the end of
Sprint 2, other developers can build songs in a full UI on their own machines. A shared URL for
them needs the deferred staging setup, and real music needs the GPU step.
