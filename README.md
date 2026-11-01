# BeatMind AI

Your personal music generator and DJ mixer: original songs and beats from a vibe or your own lyrics, in your language, at a price that fits where you live.

> **Status:** Sprint 1 (first song end to end). Sign in, describe a song and play two takes, all on your machine. Songs are short test tones from a CPU stub until a GPU runs ACE-Step ([TODO.md](TODO.md)). Sprint reports: [0](docs/09-delivery/sprint-00-report.md), [1](docs/09-delivery/sprint-01-report.md).

## Documentation

Non-code release tasks (accounts, keys, GPU runs, listeners, legal) are tracked in [TODO.md](TODO.md).

Start at [docs/README.md](docs/README.md) for the package index, evidence rules, ID scheme and batch plan.

| Area | Document |
| --- | --- |
| Product | [PRD v2.1](docs/02-product/prd.md) |
| Architecture | [TDD v2.0](docs/03-architecture/tdd.md) and [ADRs](docs/03-architecture/adr/) |
| Market | [Market, launch-market and competitor analysis](docs/01-market/market-and-competitor-analysis.md) |
| Governance | [Decision log](docs/00-governance/decision-log.md), [open questions](docs/00-governance/open-questions.md) |
| Registers | [Assumptions](docs/registers/assumptions.csv), [risks](docs/registers/risks.csv), [sources](docs/registers/sources.csv), [Excel workbook](docs/registers/beatmind-registers.xlsx) |

## Code

| Folder | What | Stack | Tests |
| --- | --- | --- | --- |
| [`api/`](api/) | Core API (ARCH-02): sign-in, song generation, job queue, pricing, waitlist, metrics | Java 21, Spring Boot 4.1, Spring Modulith, Flyway, PostgreSQL 16 | JUnit 5 (`mvn verify`) |
| [`workers/`](workers/) | Generation workers (ARCH-04): queue claim, CPU stub and ACE-Step runners, GPU benchmark and Hindi spike kits | Python 3.11+ | pytest, ruff |
| [`web/`](web/) | Web app (ARCH-01): waitlist page in English and Hindi; "make a song" developer preview | Next.js 16, React 19, TypeScript | `npm test`, `npm run build` |
| [`infra/`](infra/) | Staging and production on Cloudflare and Oracle Cloud; local monitoring (`infra/observability/`) | OpenTofu, Prometheus, Grafana | `tofu validate` |

### Run it locally

You need Docker. Then:

```sh
docker compose up --build
```

Then open:

- http://localhost:3000/en/create to **make a song**: sign in with any email (locally the code is filled in for you, no email is sent), enter an 18+ date of birth, describe a song and play the two takes. The worker turns each request into two short test tones, so the whole flow works without a GPU.
- http://localhost:3000/en/admin/jobs for failed jobs (sign in as `admin@example.com`).
- http://localhost:3000 for the waitlist page and http://localhost:8080/v1/pricing?country=IN for the API.

`scripts/dev/first-song-smoke.sh` checks the same flow from the command line.

Monitoring (US-004): `TRACING_EXPORT_ENABLED=true docker compose --profile observability up --build` adds Grafana at http://localhost:3001 (dashboard "BeatMind pipeline"), Prometheus at http://localhost:9090 and Jaeger traces at http://localhost:16686.

Real songs: run the ACE-Step 1.5 server on a GPU box, then `BEATMIND_RUNNER=acestep ACESTEP_URL=http://<gpu-box>:8001 docker compose up`.

To work on one part:

```sh
docker compose up -d postgres            # database for tests
cd api && mvn verify                     # Java tests
cd workers && pip install -e ".[dev]" && pytest && ruff check .
cd web && npm ci && npm test && npm run dev
```

CI runs all of this on every pull request, plus an RTM check, a secret scan, dependency review and a Docker Compose smoke test.
