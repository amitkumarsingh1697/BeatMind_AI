# Data protection impact assessment (DPIA)

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 2), 2026-10-28 |
| Scope | BeatMind MVP closed beta (India, 18+), as in [PRD v2.1](../02-product/prd.md) |
| Frameworks | India DPDP Act 2023 and Rules 2025 (COMP-IN-04); GDPR structure used as a checklist so the same document extends to EU launch (COMP-EU-02) |
| Status | Draft for counsel and the future data protection contact. Nothing is processed yet (DEC-001). |

## 1. Why a DPIA

The product processes personal data at scale, uses AI on user-provided text, keeps a long-lived provenance record, and handles payments. Voice data is out of scope for the MVP because voice models were removed (FEAT-30). A DPIA is required again before FEAT-30 ships, because voice data is biometric-like and high risk.

## 2. Data inventory

| Category | Data items | Source | Purpose | Lawful basis (DPDP / GDPR) | Retention |
| --- | --- | --- | --- | --- | --- |
| Identity | Email, Google account ID, display name, optional phone | User | Account, sign-in, security | Consent for the service (DPDP); contract (GDPR) | Account life plus 30 days |
| Age | Year and month of birth | User declaration | Enforce 18+ (REQ-F-53) | Legal obligation | Account life |
| Location | Country from IP and billing country | Derived; payment provider | Pricing band, tax, sanctions | Legitimate use for the contract (DPDP); contract and legal obligation (GDPR) | Account life |
| Content | Prompts, lyrics, settings, songs, edits | User; generated | Provide the service; provenance | Consent for the service; contract | Account life; ledger see section 6 |
| Provenance | Ledger events with user ID and timestamps | System | Prove origin and AI labelling (COMP-IN-02) | Legal obligation and legal claims | See section 6 |
| Payments | Plan, provider customer ID, payment status (no card or UPI data) | Payment provider | Billing | Contract; legal obligation (tax) | Tax-law period (to confirm) |
| Technical | IP address, device fingerprint, user agent, logs | System | Security, abuse prevention | Legitimate use (security) | 30 days logs; fingerprints account life |
| Analytics | Product events | System | Improve product | Consent (opt-in) | 12 months, then aggregated |
| Training opt-in | Flag and the user's songs | User | Improve models only if opted in (DEC-009) | Explicit consent | Until withdrawn |
| Waitlist (pre-launch, US-007) | Email, country, language, chosen use case, source channel, price variant shown, consent version, 18+ confirmation | User; country from the edge | Tell people when the beta opens; price research (EXP-02) | Consent (DPDP and GDPR) | Until 6 months after public launch, or sooner on request [A, counsel to confirm] |
| Waitlist visit counts | Day, country, price variant, source channel, count. No identifiers | System | Visit-to-sign-up rate for EXP-02 | Not personal data | Until EXP-02 is closed |
| Support and reports | Messages, takedown notices | User; third parties | Support, legal process | Legal obligation; legitimate use | 3 years (to confirm) |

## 3. Data flows and processors

| Processor or recipient | Data | Location (to confirm, C-03) | Safeguard |
| --- | --- | --- | --- |
| Cloud hosting provider (ADR-006) | All application data | India region preferred | Data processing agreement; encryption |
| Cloudflare (CDN, storage) | Audio, IPs | Global edge | Data processing agreement |
| Razorpay; merchant of record | Billing data | India; provider regions | Provider is a separate controller for payment data |
| Google (sign-in; Lyria API fallback) | Account ID; prompts and lyrics when fallback is used | Google regions | Disclose fallback in privacy notice; no personal data in prompts beyond user text |
| Email provider | Email address | Provider region | Data processing agreement |
| PostHog (if cloud) | Pseudonymous events | Provider region | Opt-in only; or self-host |

Cross-border transfer: the DPDP Act allows transfers except to countries the government restricts [A, confirm with counsel]. GDPR transfers need safeguards before EU launch.

## 4. Necessity and proportionality

- No phone number unless the user chooses phone sign-in or claims free commercial licences (anti-abuse, TM-12).
- Date of birth stored as year and month only.
- No voice, face or biometric data in the MVP.
- Analytics are opt-in; security logs are kept 30 days.
- Prompts and lyrics are never used for training without explicit opt-in.

## 5. Risks to individuals

| ID | Risk | Likelihood | Severity | Mitigation | Residual |
| --- | --- | --- | --- | --- | --- |
| DP-01 | Unreleased songs or lyrics leak through an authorisation bug | Medium | High | REQ-SEC-02, REQ-SEC-03; authorisation tests | Low |
| DP-02 | Account takeover | Medium | Medium | REQ-SEC-01, REQ-SEC-06 | Low |
| DP-03 | Minors use the service without parental consent | Medium | Medium | 18+ declaration; account review on reports; parental-consent flow before opening to minors | Medium |
| DP-04 | Personal data in prompts sent to the Lyria fallback | Low | Medium | Privacy notice; fallback only for content generation; no account data sent | Low |
| DP-05 | Provenance ledger keeps identifiers after erasure request | Medium | Low | Pseudonymise user ID in ledger; keep only what legal claims need (section 6) | Low, counsel to confirm |
| DP-06 | Breach not reported in time | Low | High | Incident runbook (REQ-SEC-15); 24-hour assessment | Low |
| DP-07 | Profiling for pricing by location seen as unfair | Low | Low | Transparent pricing by billing country; no individual price discrimination | Low |

## 6. Provenance ledger and erasure

The ledger is evidence that a song was AI-generated and who contributed what, which Indian rules require (COMP-IN-02). On an erasure request:

1. Delete the account, songs and files within 30 days.
2. Replace the user ID in ledger events with a one-way pseudonym and delete the mapping.
3. Keep the hash chain intact so other songs' evidence remains valid.
4. Keep certificates for songs already released by the user only as a hash and status, so third parties can still verify them.

Counsel must confirm this meets DPDP erasure duties (COMP-IN-04, question 4 in the compliance README).

## 7. Data principal rights

| Right | How it is met | Response target |
| --- | --- | --- |
| Access and summary | Self-serve data export (JSON plus files) | Immediate download; manual cases within 30 days |
| Correction | Profile settings | Immediate |
| Erasure | Self-serve deletion; section 6 | 30 days |
| Withdraw consent | Settings toggles for analytics, marketing, training | Immediate |
| Grievance | Grievance officer contact (COMP-IN-01, COMP-IN-04) | Acknowledge in 24 hours |
| Nominate (DPDP) | Nominee field in settings [A, confirm requirement] | |

## 8. Sign-off

| Role | Name | Date | Decision |
| --- | --- | --- | --- |
| Founder | | | |
| Counsel | | | |
| Engineering lead | | | |
