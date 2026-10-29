# Architecture decision records

| ID | Title | Status |
| --- | --- | --- |
| [ADR-001](ADR-001.md) | Modular monolith plus GPU worker pool | Proposed |
| [ADR-002](ADR-002.md) | Languages: Java 21 with Spring Boot for the core, Python for model workers, TypeScript for the web | Proposed |
| [ADR-003](ADR-003.md) | Web: Next.js with React and TypeScript | Proposed |
| [ADR-004](ADR-004.md) | Data stores: PostgreSQL, Valkey, Cloudflare R2 | Proposed |
| [ADR-005](ADR-005.md) | Job queue: PostgreSQL first, behind an interface | Proposed |
| [ADR-006](ADR-006.md) | Hosting: free tiers first, containers everywhere, Kubernetes after funding | Proposed |
| [ADR-007](ADR-007.md) | Model strategy: commercially licensed open-weight models, no foundation training in Phase 1 | Proposed |
| [ADR-008](ADR-008.md) | Identity: Spring Security with Google sign-in and email one-time codes | Proposed |
| [ADR-009](ADR-009.md) | Payments: Razorpay in India, a merchant of record elsewhere | Proposed |
| [ADR-010](ADR-010.md) | Provenance: hash-chained ledger, C2PA content credentials, AudioSeal watermark | Proposed |
| [ADR-011](ADR-011.md) | Observability: OpenTelemetry with the Grafana stack | Proposed |
| [ADR-012](ADR-012.md) | Delivery: GitHub Actions, Docker, OpenTofu, trunk-based development | Proposed |

Status moves from Proposed to Accepted when the developers review it in Sprint 0.
