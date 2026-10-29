# Open questions

Version 0.2, 2026-10-29. All six follow-ups, Q-F01 to Q-F06, were answered on 2026-10-29 (DEC-022 to DEC-027).

Originally version 0.1, 2026-10-28. The first round (Q-B01 to Q-B11, Q-H01 to Q-H12) was answered on 2026-10-28 and is recorded in the [decision log](decision-log.md). The questions below are the follow-ups. Work continues on the stated default for each, and every default is logged as an assumption.

| ID | Priority | Question | Why it matters | Default used until answered | Assumption | Blocks |
| --- | --- | --- | --- | --- | --- | --- |
| Q-F01 | Answered (DEC-022) | Where do the three founders live, and is a company registered? If not, where will it be registered? | Sets governing law in the terms, tax registration (for example GST in India), payment gateway eligibility (Razorpay needs an Indian entity), data protection regime and investor structure. | Founders in India; Indian private limited company to be registered. | A-001 | Compliance register, legal pack, financial model |
| Q-F02 | Answered (DEC-023) | How much can the founders spend each month before funding: ₹0, about ₹10,000, or about ₹50,000? | Production music generation needs a GPU. No free cloud tier offers one for production use (RISK-004). | ₹0. Rely on startup credit programmes and a small invite-only beta with capped daily generations. | A-002 | Financial model, hosting plan sizing |
| Q-F03 | Answered (DEC-024) | Does the January 2027 target mean an investor demo and closed beta rather than a public launch? | A public launch from zero in about nine weeks with three people is not credible to investors and risks an unstable product. | Yes: demo and closed beta in January 2027; public launch date set in the scrum plan. | A-003 | Scrum plan, roadmap, pitch deck |
| Q-F04 | Answered (DEC-027) | Is it acceptable to launch on open-weight models whose training data cannot be independently verified, with that risk disclosed? | It is the only near-zero-cost route (DEC-004, DEC-005). It creates legal exposure that terms, investor material and the risk register must disclose. | Yes for the beta, with similarity screening, and a plan to move to licensed data after funding. | A-004 | ADR-007, legal pack |
| Q-F05 | Answered (DEC-025) | Are the 5 commercially usable free songs per month, or 5 for the lifetime of the account? | Changes the free-tier economics, abuse risk and conversion design. | 5 per calendar month, with "Made with BeatMind" attribution required. | A-005 | PRD section 9, legal pack |
| Q-F06 | Answered (DEC-026) | Do the three developers work full-time, and roughly how many hours a week each? | Sprint capacity and the realistic launch date depend on it. | 40 hours a week each. | A-010 | Scrum plan |

## External checks we will run ourselves

These need no answer from the founder. They are run during the batch that depends on them.

| ID | Check | Batch |
| --- | --- | --- |
| C-01 | Trademark search for the chosen name in India (IP India), the US (USPTO) and the WIPO Global Brand Database, classes 9, 41 and 42 | 1 (started) and 4 |
| C-02 | Read each selected model's licence file and model card directly from the publisher's repository and archive a dated copy | 2 |
| C-03 | Confirm current free-tier limits of each hosting provider in ADR-006 | 2 |
| C-04 | Confirm payment gateway support for purchasing-power-parity pricing and for each launch country | 4 |
| C-05 | Confirm distributor AI-content policies (DistroKid, TuneCore, CD Baby) for India and global releases | 4 |
| C-06 | Confirm EU AI Act Article 50 code of practice status before serving EU users | 2 |
| C-07 | Confirm the DPDP Rules 2025 phase dates against the official Gazette text | 2 |
