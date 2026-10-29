# BeatMind AI

Your personal music generator and DJ mixer: original songs and beats from a vibe or your own lyrics, in your language, at a price that fits where you live.

> **Status:** planning. No product code exists yet. The repository currently holds the product, engineering, market and governance documentation package.

## Documentation

Start at [docs/README.md](docs/README.md) for the package index, evidence rules, ID scheme and batch plan.

| Area | Document |
| --- | --- |
| Product | [PRD v2.0](docs/02-product/prd.md) |
| Architecture | [TDD v2.0](docs/03-architecture/tdd.md) and [ADRs](docs/03-architecture/adr/) |
| Market | [Market, launch-market and competitor analysis](docs/01-market/market-and-competitor-analysis.md) |
| Governance | [Decision log](docs/00-governance/decision-log.md), [open questions](docs/00-governance/open-questions.md) |
| Registers | [Assumptions](docs/registers/assumptions.csv), [risks](docs/registers/risks.csv), [sources](docs/registers/sources.csv), [Excel workbook](docs/registers/beatmind-registers.xlsx) |

## Planned stack

Java 21 and Spring Boot (core API), Python (model workers), Next.js (web), PostgreSQL, Valkey, S3-compatible object storage. See [ADR index](docs/03-architecture/adr/README.md).
