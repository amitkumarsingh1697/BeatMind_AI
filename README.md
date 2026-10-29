# BeatMind AI

Your personal music generator and DJ mixer: original songs and beats from a vibe or your own lyrics, in your language, at a price that fits where you live.

> **Status:** Sprint 0 (foundations). The code skeleton, local stack, CI and the waitlist page exist. Music generation runs on a CPU stub until the ACE-Step runner lands in Sprint 1.

## Documentation

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
| [`api/`](api/) | Core API (ARCH-02): pricing, waitlist, job queue | Java 21, Spring Boot 4.1, Spring Modulith, Flyway, PostgreSQL 16 | JUnit 5 (`mvn verify`) |
| [`workers/`](workers/) | Generation workers (ARCH-04): queue claim, CPU stub runner, GPU benchmark and Hindi spike kits | Python 3.11+ | pytest, ruff |
| [`web/`](web/) | Web app (ARCH-01): waitlist page in English and Hindi | Next.js 16, React 19, TypeScript | `npm test`, `npm run build` |
| [`infra/`](infra/) | Staging and production on Cloudflare and Oracle Cloud | OpenTofu | `tofu validate` |

### Run it locally

You need Docker. Then:

```sh
docker compose up --build
```

Open http://localhost:3000 for the waitlist page and http://localhost:8080/v1/pricing?country=IN for the API. The worker turns queued jobs into a short test tone, so the whole flow works without a GPU.

To work on one part:

```sh
docker compose up -d postgres            # database for tests
cd api && mvn verify                     # Java tests
cd workers && pip install -e ".[dev]" && pytest && ruff check .
cd web && npm ci && npm test && npm run dev
```

CI runs all of this on every pull request, plus an RTM check, a secret scan, dependency review and a Docker Compose smoke test.
