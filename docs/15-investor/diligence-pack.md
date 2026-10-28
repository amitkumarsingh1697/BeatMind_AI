# Investor diligence pack (data-room index)

Version 0.1 (Batch 4), 2026-10-28. This is the list of what an early-stage investor will ask for, with the honest status of each item today. "Exists" means the item is in this repository; "Missing" means it does not exist yet. Nothing is marked done unless it is.

## 1. Company and legal

| ID | Item | Status | Where or next step |
| --- | --- | --- | --- |
| DD-01 | Certificate of incorporation, constitution documents | Missing | Q-F01; finance checklist |
| DD-02 | Cap table and share register | Missing | After incorporation |
| DD-03 | Founders' agreement (roles, vesting, leaver terms) | Missing | Before fundraising |
| DD-04 | IP assignment from all founders and developers to the company | Missing | **Critical**: code is being written in a public repository now; sign before Sprint 0 ends |
| DD-05 | Employment or contractor agreements | Missing | |
| DD-06 | Board minutes and resolutions | Missing | |
| DD-07 | Tax registrations (PAN, GST) | Missing | COMP-IN-07 |
| DD-08 | Startup India (DPIIT) recognition | Missing | Optional |

## 2. Product and technology

| ID | Item | Status | Where |
| --- | --- | --- | --- |
| DD-10 | Product requirements | Exists (draft) | [PRD v2.1](../02-product/prd.md) |
| DD-11 | Architecture and decisions | Exists (draft) | [TDD v2.0](../03-architecture/tdd.md), [ADRs](../03-architecture/adr/) |
| DD-12 | Working product or demo | Missing | Target: January 2027 demo (A-003) |
| DD-13 | Source code with history | Missing (repository holds documents only) | Sprint 0 onward |
| DD-14 | Open-source licence inventory of dependencies | Missing | CI licence scan (COMP-GL-02) |
| DD-15 | Model register with archived licence files | Partly exists | [Model register](../06-ai/model-register.md); licence copies pending (C-02) |
| DD-16 | Security posture: threat model, test results | Threat model exists; results missing | [Threat model](../04-security/threat-model.md) |
| DD-17 | Uptime and incident history | Missing | After beta (SRE handbook) |

## 3. Market and customers

| ID | Item | Status | Where |
| --- | --- | --- | --- |
| DD-20 | Market and competitor analysis | Exists (draft) | [Market analysis](../01-market/market-and-competitor-analysis.md) |
| DD-21 | Customer evidence (interviews, waitlist, pilots) | **Missing** (DEC-012) | [Validation plan](../11-validation/validation-plan.md), [evidence log](../11-validation/evidence-log.csv) |
| DD-22 | Product metrics (activation, retention, keep rate) | Missing | Beta analytics (EXP-06) |
| DD-23 | Go-to-market plan | Exists (draft) | [GTM](../13-business/go-to-market.md) |

## 4. Finance

| ID | Item | Status | Where |
| --- | --- | --- | --- |
| DD-30 | Financial model | Exists (draft, hypotheses) | [Financial model](../13-business/pricing-and-financial-model.md) |
| DD-31 | Historical accounts | Not applicable yet (no company, no revenue) | |
| DD-32 | Measured unit cost per song | Missing | Sprint 0 benchmark (US-005) |
| DD-33 | Use of funds | Exists as shares; no round size | Financial model 4.2 |

## 5. Legal, rights and compliance

| ID | Item | Status | Where |
| --- | --- | --- | --- |
| DD-40 | Terms, rights terms, privacy, acceptable use, takedown | Exists (draft, not reviewed by counsel) | [Legal pack](../14-legal/README.md) |
| DD-41 | Counsel's opinion on the rights model and training-data risk | Missing | Legal pack points L-01 to L-03 |
| DD-42 | Compliance register | Exists (draft) | [Compliance](../05-compliance/README.md) |
| DD-43 | DPIA | Exists (draft) | [DPIA](../05-compliance/dpia.md) |
| DD-44 | Licences for training data | Not applicable in Phase 1 (no own training); model publishers' statements recorded | RISK-001 |
| DD-45 | Trademark search and filing | Missing | C-01 |
| DD-46 | Domain ownership | Missing | Brand research |

## 6. Team

| ID | Item | Status |
| --- | --- | --- |
| DD-50 | Founder and team biographies | Missing |
| DD-51 | Hiring plan | Partly exists (use of funds; pitch deck slide 13) |

## 7. What an investor will see as the biggest gaps today

1. No product, code or demo yet (DD-12, DD-13).
2. No customer evidence (DD-21).
3. No company, IP assignment or cap table (DD-01 to DD-04).
4. Rights model and training-data risk without counsel's opinion (DD-41).
5. Unit costs not measured (DD-32).

The investor readiness assessment (Batch 5) scores these and sets the order to close them.
