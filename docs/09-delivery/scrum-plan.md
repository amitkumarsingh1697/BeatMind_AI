# Scrum plan

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 3), 2026-10-28 |
| Perspective | Delivery manager |
| Backlog | [product-backlog.csv](product-backlog.csv): 43 user stories (US-001 to US-043) in 12 epics, 207 story points |
| Assumptions | A-003 (January 2027 is investor demo and closed beta), A-010 (3 developers at 30 to 50 hours a week, DEC-026; plans use 30), A-001 (Indian entity for payments) |
| Status | Draft. Dates are plans, not commitments; velocity is unknown until Sprint 1 ends. |

## 1. Team and roles

| Role | Who | Responsibilities |
| --- | --- | --- |
| Product Owner | Amit Kumar Singh (founder) | Orders the backlog, accepts stories, answers open questions, runs interviews with the team |
| Scrum Master | Rotates among the 3 developers each sprint | Runs ceremonies, removes blockers, owns the board; decides with developers what to build when (DEC-010) |
| Developers (3 seniors) | Names to be added | Build, test, operate; on-call rotation (SRE handbook) |
| AI assistant (Claude) | Drafting and review support | Documentation, code drafts, test drafts, reviews; all output reviewed by a developer before merge |

Suggested ownership to reduce key-person risk (RISK-010), with a named backup for each:

| Area | Primary | Backup |
| --- | --- | --- |
| Core API (Java): identity, rights, billing, provenance | Developer A | Developer B |
| GPU and audio workers (Python), models | Developer B | Developer C |
| Web app, UX, accessibility, i18n | Developer C | Developer A |

## 2. Cadence

| Event | When | Length |
| --- | --- | --- |
| Sprint | 2 weeks, Monday to Friday of the second week | |
| Sprint planning | Sprint day 1 | 2 hours |
| Daily stand-up | Daily, async in chat plus 15-minute call twice a week (remote team, DEC-002) | 15 minutes |
| Backlog refinement | Sprint day 6 | 1 hour |
| Sprint review and demo | Last day, with the founder | 1 hour |
| Retrospective | Last day | 45 minutes |

## 3. Definition of Ready and Definition of Done

**Ready:** the story has acceptance criteria, linked REQ IDs, test cases in the RTM, design (for UI stories), no unanswered blocking question, and an estimate.

**Done:** code merged to main through review; CI quality gates pass (testing strategy section 5); RTM test cases tagged and passing; feature flag or release note in place; docs updated; deployed to staging; accessibility check passed for UI stories; no new high security findings.

## 4. Sprint plan

Velocity assumption: 35 to 45 points per sprint at 40 hours a week each, for three senior developers with AI assistance [E]. The founder confirmed 30 to 50 hours a week (DEC-026). At the 30-hour floor, expect about 26 to 34 points [C: 35 to 45 x 30/40], so Sprints 2, 3 and 5 are over capacity at the floor and the cut line below applies first. Recalibrate after Sprint 1.

| Sprint | Dates (2026-2027) | Goal | Points | Key stories |
| --- | --- | --- | --- | --- |
| SPRINT-00 | 2 Nov to 13 Nov | Foundations and risk spikes: can the model do Hindi cheaply? | 22 | Repo and CI, local stack, infrastructure as code, model benchmark, Hindi spike, waitlist page |
| SPRINT-01 | 16 Nov to 27 Nov | First song end to end in staging | 26 | Sign-in, generation API and queue, observability, interviews |
| SPRINT-02 | 30 Nov to 11 Dec | Songs are labelled, provable and downloadable | 39 | Ledger, labels and watermark, custom lyrics, library, player, downloads, refunds, caps |
| SPRINT-03 | 14 Dec to 25 Dec | Safe and multilingual; G1 listening tests | 42 | AI lyric writer, Hindi interface, moderation, similarity screen, free commercial licences, admin console, G1 tests |
| SPRINT-04 | 28 Dec to 8 Jan | Beta hardening; gate G2; investor demo | 33 | Beat mode, queue position, sharing, takedowns, data export, load and security tests, accessibility, demo |
| **Closed beta** | **from 11 Jan 2027** | Invite-only India beta (A-003) | | |
| SPRINT-05 | 11 Jan to 22 Jan | Payments and ownership (needs Indian entity, A-001) | 35 | Price bands, Razorpay, ownership and chargeback rule, cancellation, editor, verify page, Lyria fallback |
| SPRINT-06 | 25 Jan to 5 Feb | International payments and stems | 10 plus beta fixes | Merchant of record, stems |
| Public launch | After gate G3; earliest late February 2027 [H] | | | |

Sprint 3 and 4 fall over the year-end holidays. Expect lower capacity; the cut line below protects the beta.

## 5. Cut line if velocity is lower

If Sprint 1 velocity is under 30 points, these move after the beta start, in this order: Beat mode (US-015), sharing (US-024), Lyria fallback (US-016), verify page (US-021), data export (US-011, but deletion stays). The beta must not start without: labels and provenance (US-019, US-020), moderation (US-028), similarity screen (US-029), caps (US-030), takedowns (US-032), and the load and security baseline (US-042).

## 6. Dependencies outside the team

| Dependency | Needed by | Owner | Question |
| --- | --- | --- | --- |
| GPU access (credits or rented) | SPRINT-00 | Founder | Q-F02 |
| Company registration and Razorpay account | SPRINT-05 | Founder | Q-F01 |
| Counsel review of terms and certificate wording | Before paid launch | Founder | Compliance README questions |
| Native Hindi copywriter and listeners for G1 | SPRINT-03 | Founder | |
| Domain and trademark search | SPRINT-00 | Founder | C-01 |

## 7. Tooling

GitHub Issues and Projects for the board (free for public repositories), labels per epic and sprint, milestones per sprint. Each user story becomes an issue titled `US-0xx: <story>`; pull requests reference the issue and REQ IDs.

## 8. Reporting

The Scrum Master posts a sprint report at each review: goal met or not, points done, velocity trend, burndown, risks changed, decisions needed from the founder.
