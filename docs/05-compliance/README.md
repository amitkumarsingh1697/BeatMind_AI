# Compliance and regulatory register

Version 0.1 (Batch 2), 2026-10-28. The register is [compliance-register.csv](compliance-register.csv) (24 entries, COMP-IN, COMP-EU, COMP-US, COMP-GL, COMP-SA).

**This is not legal advice.** It is a working list for counsel. Each row says whether the obligation is externally sourced (a law-firm or news summary we read) or an assumption from analyst knowledge. The founders asked that no regulatory obligation be invented, so every assumption-tagged row must be confirmed by a qualified lawyer before anyone relies on it.

It assumes an Indian company (A-001, question Q-F01). If the founders register elsewhere, add that country's rows and re-check COMP-IN-07, COMP-IN-08 and COMP-IN-10.

## What applies to the India beta

| Priority | Obligation | Why it matters now | Built by |
| --- | --- | --- | --- |
| 1 | COMP-IN-02: label AI audio, embed provenance markers that cannot be removed, 3-hour takedown on orders | In force since 2026-02-20 and aimed at exactly this product type | REQ-F-30, REQ-F-33, REQ-F-54, ADR-010 |
| 2 | COMP-IN-04: DPDP notice, consent, under-18 parental consent, breach intimation | Phasing in now; beta is 18+ to avoid child data | REQ-F-53, [DPIA](dpia.md) |
| 3 | COMP-IN-05 and COMP-IN-09: copyright and personality rights | Core legal exposure (RISK-001, RISK-011) | REQ-F-26, REQ-F-50, REQ-F-52 |
| 4 | COMP-IN-01: grievance officer, published terms and policies | Needed to keep intermediary safe harbour | Batch 4 legal pack |
| 5 | COMP-IN-06 to COMP-IN-08: consumer, tax, recurring-payment rules | Needed before taking money | ADR-009, financial model |

## What must be done before each later wave

| Wave | Gate items |
| --- | --- |
| Paid launch in India | COMP-IN-06, COMP-IN-07, COMP-IN-08, COMP-GL-03, COMP-GL-04 confirmed |
| Wave 2 (Pakistan, Bangladesh, Nepal and others) | COMP-SA-01 local counsel review |
| Wave 3 (EU, UK, US marketing) | COMP-EU-01 to COMP-EU-04, COMP-US-02, Article 50 marking verified (C-06) |

## Questions for counsel

1. Is BeatMind an "intermediary" under the IT Act for user-generated songs it hosts, and which IT Rules duties apply to a generator that also hosts?
2. What exact label wording and placement satisfies "prominently labelled" for audio-only content?
3. Does the free-plan licence plus paid-plan assignment (DEC-009) work under section 2(d)(vi) of the Copyright Act, and how should the certificate be worded?
4. Can the provenance ledger keep pseudonymised records after an erasure request (DPIA section 6)?
5. Which disclosures about open-model training data (RISK-001) are needed in the terms and in investor documents?
