# BeatMind AI documentation package

| Item | Value |
| --- | --- |
| Package version | 0.2 (Batch 1 foundation; Batch 2 security, compliance, quality, UX) |
| Date | 2026-10-28 |
| Owner | Amit Kumar Singh (founder) |
| Prepared with | Claude (AI assistant). Everything here is a draft for founder review. Legal, tax and financial content needs sign-off from a qualified professional before anyone relies on it. |
| Status | Draft. Not for external distribution until the GA readiness report and the investor readiness assessment (Batch 5) are complete. |

## What this package is

This package holds the end-to-end product, engineering, compliance, go-to-market and investor documents for BeatMind AI. It is delivered in versioned batches. Each batch keeps stable IDs, so a requirement, risk or test written in Batch 1 keeps the same ID in every later batch.

Nothing has been built yet (decision DEC-001). Every capability described here is **planned**, not complete. The GA readiness report (Batch 5) will separate finished work from remaining gaps.

## Evidence classification

Every material claim carries one of these tags.

| Tag | Meaning |
| --- | --- |
| **[V] Verified** | Checked directly by us, for example a domain registry lookup or the contents of this repository, with the date of the check. |
| **[SS] Source-stated** | Stated by the party the claim is about, for example a model card describing its own licence or training data. Not independently checked. |
| **[ES] Externally sourced** | Reported by a third party, such as a news outlet, analyst or law firm, with a link and date in `registers/sources.csv`. |
| **[C] Calculated** | Derived by arithmetic from other tagged figures. The inputs are named. |
| **[A] Assumption** | Treated as true for planning; must be confirmed. Logged in `registers/assumptions.csv`. |
| **[H] Hypothesis** | A belief to be tested by an experiment, such as a pricing test or user interviews. |
| **[E] Estimate** | A judgement-based number with a stated range. |
| **[U] Unknown** | Not known and not assumed. Work that depends on it is flagged. |
| **[NA] Not applicable** | The item does not apply to BeatMind's current scope. |

Rules this package follows: no invented customers, revenue, traction, measurements, certifications, market figures, regulatory obligations, investment commitments or completed capabilities. No fundraising amount or valuation is stated until the founders decide one.

## ID scheme

| Prefix | Used for | Register |
| --- | --- | --- |
| DEC- | Founder and project decisions | `00-governance/decision-log.md` |
| Q- | Open questions | `00-governance/open-questions.md` |
| A- | Assumptions | `registers/assumptions.csv` |
| SRC- | Sources | `registers/sources.csv` |
| RISK- | Risks | `registers/risks.csv` |
| REQ- | Functional and non-functional requirements | `02-product/prd.md` |
| FEAT- | Features | `02-product/prd.md` |
| US- | User stories | Batch 3 (scrum plan) |
| ARCH- | Architecture components | `03-architecture/tdd.md` |
| API- | API endpoints | `03-architecture/tdd.md` |
| INT- | External integrations | `03-architecture/tdd.md` |
| ADR- | Architecture decision records | `03-architecture/adr/` |
| METRIC- | Product and business metrics | `02-product/prd.md` |
| COMP- | Compliance obligations | Batch 2 (compliance register) |
| TC- | Test cases | Batch 2 (testing strategy and RTM) |
| BUG- | Defects and review findings | Batch 2 onward |
| SPRINT- | Sprints | Batch 3 (scrum plan) |

## Deliverables and status

| # | Deliverable | Batch | Status | Location |
| --- | --- | --- | --- | --- |
| 1 | Product requirements document (v2.1) and entitlement matrix | 1, 2 | Draft | [02-product/prd.md](02-product/prd.md), [entitlement-matrix.csv](02-product/entitlement-matrix.csv) |
| 2 | Technical design document (v2.0) with ADRs | 1 | Draft | [03-architecture/tdd.md](03-architecture/tdd.md) |
| 3 | Pitch deck (rewrite) | 4 | Not started | |
| 4 | Market, launch-market and competitor analysis | 1 | Draft | [01-market/market-and-competitor-analysis.md](01-market/market-and-competitor-analysis.md) |
| 5 | Scrum plan (epics, stories, sprints) | 3 | Not started | |
| 6 | Compliance and regulatory register | 2 | Draft | [05-compliance/](05-compliance/README.md) |
| 7 | Testing strategy and requirements traceability matrix | 2 | Draft | [07-quality/testing-strategy.md](07-quality/testing-strategy.md), [rtm.csv](07-quality/rtm.csv) |
| 8 | Validation plan (user, market, model quality) | 3 | Not started | |
| 9 | Security architecture and threat model | 2 | Draft | [04-security/threat-model.md](04-security/threat-model.md) |
| 10 | SRE handbook (SLOs, runbooks, incident process) | 3 | Not started | |
| 11 | UX and accessibility specification | 2 | Draft | [08-ux/ux-accessibility-spec.md](08-ux/ux-accessibility-spec.md) |
| 12 | Go-to-market plan | 4 | Not started | |
| 13 | Pricing (purchasing-power parity) and financial model | 4 | Not started (pricing principles in PRD section 9) | |
| 14 | Legal pack: terms of service, licence and rights certificate, privacy policy, acceptable use, takedown policy | 4 | Not started | |
| 15 | Investor diligence pack | 4 | Not started | |
| 16 | Customer documentation (help centre, onboarding) | 3 | Not started | |
| 17 | Decision log | 1 | Draft | [00-governance/decision-log.md](00-governance/decision-log.md) |
| 18 | Open questions | 1 | Draft | [00-governance/open-questions.md](00-governance/open-questions.md) |
| 19 | Assumption register | 1 | Draft | [registers/assumptions.csv](registers/assumptions.csv) |
| 20 | Risk register | 1 | Draft | [registers/risks.csv](registers/risks.csv) |
| 21 | Source register | 1 | Draft | [registers/sources.csv](registers/sources.csv) |
| 22 | Brand and name research | 1 | Draft | [01-market/brand-name-research.md](01-market/brand-name-research.md) |
| 23 | Glossary | 1 | Draft | [registers/glossary.md](registers/glossary.md) |
| 24 | Data protection impact assessment | 2 | Draft | [05-compliance/dpia.md](05-compliance/dpia.md) |
| 25 | Model cards and AI model register | 2 | Draft | [06-ai/model-register.md](06-ai/model-register.md) |
| 28 | Review findings register (BUG-001 to BUG-090) | 2 | Draft | [registers/review-findings.csv](registers/review-findings.csv) |
| 26 | GA readiness report | 5 | Not started | |
| 27 | Investor readiness assessment | 5 | Not started | |

All registers are also combined in [registers/beatmind-registers.xlsx](registers/beatmind-registers.xlsx), generated from the CSV files by `registers/build_workbook.py`.

## Batch plan

| Batch | Contents | Depends on |
| --- | --- | --- |
| 1 | Governance, registers, market and competitor analysis, brand research, PRD v2.0, TDD v2.0 with ADRs | Founder answers of 2026-10-28 |
| 2 | Security and threat model, compliance register, DPIA, model cards, testing strategy and RTM, UX and accessibility | Batch 1; Q-F01 (founder location) for the compliance register |
| 3 | Scrum plan, SRE handbook, validation plan, customer documentation | Batch 1 and 2 |
| 4 | Pricing and financial model, GTM, legal pack, diligence pack, pitch deck | Q-F01, Q-F02 (monthly spend) |
| 5 | GA readiness report, investor readiness assessment | All batches |

## Source documents

| ID | Document | Date |
| --- | --- | --- |
| SRC-001 | Suno teardown and market gap research | 2026-10-22 |
| SRC-002 | BeatMind AI PRD v1.0 (superseded by v2.0 here) | 2026-10-22 |
| SRC-003 | BeatMind AI TDD v1.0 (superseded by v2.0 here) | 2026-10-22 |
| SRC-004 | BeatMind AI Pitch Deck (20 slides, 14 with placeholders) | 2026-10-25 |
| SRC-005 | BeatMind pre-launch review | 2026-10-28 |
| SRC-006 | Open questions and assumptions | 2026-10-28 |
| SRC-007 | Founder answers to the open questions | 2026-10-28 |

External sources start at SRC-100 in [registers/sources.csv](registers/sources.csv).
