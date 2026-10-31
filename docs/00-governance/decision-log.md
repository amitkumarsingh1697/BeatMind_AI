# Decision log

Version 0.1, 2026-10-28. Each entry records who decided, when, and what changes because of it. Founder answers are quoted in short form; the full text is in source SRC-007.

## Founder decisions

| ID | Date | Decision | Decided by | Consequence for the package |
| --- | --- | --- | --- | --- |
| DEC-001 | 2026-10-28 | Nothing exists yet: no code, models, designs or infrastructure. The repository `amitkumarsingh1697/BeatMind_AI` (public) holds the package and future code. | Founder | Every capability is planned, not complete. The GA readiness report starts from zero. |
| DEC-002 | 2026-10-28 | Founders and company work remotely. The first launch market is undecided, and the founder asked for a market analyst and business analyst recommendation. | Founder | Launch market is recommended in the market analysis (section 4). Company location is still open (Q-F01). |
| DEC-003 | 2026-10-28 | Target customers: artists, YouTube and content creators, music industry, producers, directors. | Founder | PRD v2.0 defines a beachhead persona order (PRD section 4) so the MVP stays focused. |
| DEC-004 | 2026-10-28 | Model strategy delegated to us, with the goal of near-zero cost. | Founder | ADR-007 selects open-weight models whose licences allow commercial use, plus a paid API fallback. |
| DEC-005 | 2026-10-28 | No training data, relationships or data budget. | Founder | BeatMind does not train a foundation model in Phase 1. It uses third-party open weights (ADR-007) and records the training-data risk (RISK-001). |
| DEC-006 | 2026-10-28 | Team: 3 senior developers plus an AI assistant. | Founder | Architecture favours few moving parts (ADR-001). Weekly hours are unknown (A-010). |
| DEC-007 | 2026-10-28 | Budget undecided. Preferred launch January 2027 if everything works and investors are found. Finance planning delegated to us. | Founder | Financial model in Batch 4. January 2027 is treated as investor demo and closed beta until Q-F03 is answered. |
| DEC-008 | 2026-10-28 | Rights: free users may generate without limit but use only 5 songs for commercial, personal or business purposes. Subscribers may use all their songs anywhere. | Founder | PRD section 9 and the legal pack (Batch 4). The "5 songs" period is open (Q-F05). |
| DEC-009 | 2026-10-28 | **Ownership: the user owns songs made on a paid plan; BeatMind receives a licence only.** Chosen on the decision card over joint ownership and BeatMind ownership. | Founder | Terms assign to the user whatever rights BeatMind holds in the output. BeatMind keeps a licence to host, process and display the song, and uses it for training only with opt-in consent. |
| DEC-010 | 2026-10-28 | Findings from the pre-launch review go to the backlog as feature requests or defects. The Scrum Master and developers decide what to build and when. | Founder | Review findings become BUG- and FEAT- items in Batch 2 and 3, not fixed scope. |
| DEC-011 | 2026-10-28 | Technology choice delegated to us. Java and Spring preferred but not required. Must scale to millions of users and stay available. | Founder | ADR-002: Java 21 with Spring Boot for the core API (JUnit 5), Python for model workers. |
| DEC-012 | 2026-10-28 | No customer evidence yet (no interviews, waitlist, letters of intent or pilots). | Founder | All demand claims are tagged Hypothesis. Validation plan (Batch 3) starts with interviews and a waitlist. |
| DEC-013 | 2026-10-28 | Prices shown by the visitor's region and adjusted for purchasing-power parity. | Founder | PRD section 9 pricing principles; full price table in Batch 4. |
| DEC-014 | 2026-10-28 | Start on free cloud tiers; move to a paid provider after funding. | Founder | ADR-006 hosting plan. GPU inference has no free production tier (RISK-004). |
| DEC-015 | 2026-10-28 | Package lives in the GitHub repo as Markdown plus CSV, JSON and XLSX. | Founder | This repository layout. |
| DEC-016 | 2026-10-28 | Mobile: responsive mobile web at launch, no native apps. | Founder (accepted default) | PRD non-functional requirements. |
| DEC-017 | 2026-10-28 | Accessibility level left to us. | Founder | We set WCAG 2.2 level AA (REQ-NFR-08). |
| DEC-018 | 2026-10-28 | Languages: as many as possible, starting with Hindi, English, French, Russian and other top music languages. | Founder | PRD section 10 language tiers. Russian payments flagged (RISK-012). |
| DEC-019 | 2026-10-28 | Working name BEATMIND AI; open to a better available name from any language, mythology or culture. | Founder | Brand research in `01-market/brand-name-research.md`. |
| DEC-020 | 2026-10-28 | Produce all production-ready documents, including those beyond the original 23. | Founder | Deliverables 24 and 25 (DPIA, model cards) added. |
| DEC-021 | 2026-10-28 | Ask any further open questions before research, planning and implementation. | Founder | Follow-up questions Q-F01 to Q-F06 in `open-questions.md`. Work continues on stated defaults meanwhile. |
| DEC-022 | 2026-10-29 | Company not yet registered; it will be registered in India. Developers are in India and work remotely. | Founder | Answers Q-F01. Confirms A-001: Indian private limited company, Indian governing law in the terms, GST, Razorpay. |
| DEC-023 | 2026-10-29 | Founders can spend ₹5,000 to ₹10,000 a month before funding. | Founder | Answers Q-F02. Read as Indian rupees, the currency the question used. Budget plan uses ₹5,000 as the floor and ₹10,000 as the ceiling (financial model 4.1). |
| DEC-024 | 2026-10-29 | January 2027 means a beta version. The final plan depends on testing and bug fixes. | Founder | Answers Q-F03. Confirms A-003: closed beta from January 2027, gated by G2; public launch only after G3. |
| DEC-025 | 2026-10-29 | The 5 commercially usable free songs are per calendar month. | Founder | Answers Q-F05. Confirms A-005; rights terms 3.3 and the entitlement matrix already use this. |
| DEC-026 | 2026-10-29 | Each developer gives 30 to 50 hours a week over 5 days. | Founder | Answers Q-F06. Sprint capacity is planned at the 30-hour floor (scrum plan section 4). |
| DEC-027 | 2026-10-29 | **Beta runs on a free open-weight model (ACE-Step 1.5) with the training-data risk disclosed.** Chosen on the decision card over a paid licensed model only and a legal opinion first. | Founder | Answers Q-F04. Confirms A-004 and ADR-007: similarity screening on every song, honest disclosure in terms and investor material, a backup model ready, and a move to licensed data after funding (RISK-001). |
| DEC-028 | 2026-10-31 | Song languages: Phase 1 covers the most popular languages worldwide, starting with Hindi, English, Hinglish, Punjabi and Urdu at beta; Phase 2 adds regional languages of every country. Long phases are split further by popularity. Refines DEC-018. | Founder | Master list in `02-product/languages.md`; PRD FEAT-23 and the languages row point to it. Each language still needs gate G1. |

## Project decisions made on the founder's behalf

These follow from delegated authority (DEC-004, DEC-007, DEC-011). The founder can reverse any of them.

| ID | Date | Decision | Reason | Reference |
| --- | --- | --- | --- | --- |
| DEC-101 | 2026-10-28 | Recommend India as the beachhead market, with English available worldwide from day one. | Highest score in the launch-market matrix. | Market analysis section 4 |
| DEC-102 | 2026-10-28 | Beachhead customers: short-form and YouTube creators first, independent artists second, producers third. Directors and the wider industry move to Phase 2. | Volume, pain and willingness to pay, given open-model quality limits. | PRD section 4 |
| DEC-103 | 2026-10-28 | Primary generation model: ACE-Step 1.5. HeartMuLa and YuE are evaluated only. MusicGen and LeVo are excluded because their licences forbid commercial use. | Licence, cost and claimed training-data provenance. | ADR-007 |
| DEC-104 | 2026-10-28 | Modular monolith (Java) plus a separate GPU worker pool (Python), joined by a job queue. | Three developers; scale through stateless replicas and queue-driven workers. | ADR-001, ADR-002 |
