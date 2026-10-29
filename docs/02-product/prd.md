# BeatMind AI product requirements document

| Item | Value |
| --- | --- |
| Version | 2.0 (supersedes v1.0 of 2026-10-22, SRC-002) |
| Date | 2026-10-28 |
| Owner | Amit Kumar Singh (founder); drafted by Claude as product manager |
| Status | Draft for founder review |
| Companion | [Technical design document v2.0](../03-architecture/tdd.md) |
| Evidence tags | See [package README](../README.md#evidence-classification) |

## 1. What changed from v1.0

| Area | v1.0 (2026-10-22) | v2.0 (this document) | Reason |
| --- | --- | --- | --- |
| Launch market | Undecided, US-leaning | India beachhead plus global English | DEC-101, market analysis section 4 |
| Primary persona | Indie artists and producers | Short-form and YouTube creators, then indie artists, then producers | DEC-102 |
| Model | Own model trained on licensed data | Open-weight models with commercial licences; paid API fallback | DEC-004, DEC-005, ADR-007 |
| Ownership | "All rights BeatMind holds" to paid users | Same principle, now a founder decision: the user owns paid songs, BeatMind keeps a licence | DEC-009 |
| Free plan | Personal use only; paid upgrade per song | 5 commercially usable songs per month with attribution | DEC-008, A-005 |
| Pricing | US$12, 29, 59 | Purchasing-power price bands by country | DEC-013 |
| Own-voice cloning | MVP P1 | Removed from Phase 1 | RISK-006, review SRC-005 |
| Native 8 stems | MVP P0 | Separated stems (post-generation) in MVP; native stems later | Open models generate mixed audio (ADR-007) |
| Languages | English at launch | Hindi, English, Hinglish at beta; more by quality gate | DEC-018 |

## 2. Vision and positioning

**Vision:** anyone, in any language, can make an original song they own and can use anywhere, at a price that fits where they live.

**Positioning:** for creators and independent artists in India and other price-sensitive markets who need original, claim-safe music, BeatMind AI is a song generator that lets paid users own and download what they make, in their own language, at local prices. Unlike Suno, which charges Indian users about the US price [ES, SRC-111], and Udio, which does not allow downloads [ES, SRC-101], BeatMind pairs local pricing and Indian-language support with ownership, provenance and creator licence certificates.

**Principles**

1. **Creator owns the result.** Paid songs belong to the user (DEC-009).
2. **Local first.** Local languages and local prices, not translated menus over a US product.
3. **Honest by default.** Every song is labelled as AI-generated and carries provenance, as Indian and EU rules require [ES, SRC-117, SRC-119].
4. **No borrowed voices.** No real artist's voice or style without a signed licence.
5. **Cheap to run.** Every feature is checked against cost per song so prices can stay low.

## 3. Problems to solve

| ID | Problem | Evidence | Tag |
| --- | --- | --- | --- |
| P-01 | AI music tools are priced for US wallets | Suno Pro ₹999 vs Spotify Premium ₹199 in India | [ES, SRC-111, SRC-116] |
| P-02 | Leading tools do not support Hindi natively | Suno and Udio lack native Hindi; Lyria 3 has it | [ES, SRC-112] |
| P-03 | Creators cannot always download, own or prove their AI songs | Udio walled garden; Suno ownership language unclear | [ES, SRC-101, SRC-001] |
| P-04 | Creators face copyright claims on videos | Content ID claims reported as a creator pain | [ES, SRC-001] |
| P-05 | New rules require AI labelling and provenance | India IT Rules (20 Feb 2026); EU Article 50 (2 Aug 2026) | [ES, SRC-117, SRC-119] |

That Indian creators will pay to solve P-01 to P-04 is a hypothesis (H-001 to H-004). It is not yet evidence (DEC-012).

## 4. Users and personas

| Persona | Phase | Jobs to be done | What wins them |
| --- | --- | --- | --- |
| **Short-form and YouTube creator** (primary) | MVP | Get original, mood-matched music for videos without claims | Fast generation from a vibe, licence certificate, local price, downloads |
| **Independent artist and lyricist** | MVP | Turn own Hindi, Hinglish or English lyrics into a demo song | Custom lyrics, language quality, ownership |
| **Producer and beat maker** | MVP (beats), Phase 2 (depth) | Create beats, loops and stems for a session | Instrumental mode, BPM and key control, stems |
| **Director, ad agency, studio** | Phase 2 | Background score and jingles to a brief, with revisions and invoices | Team workspace, brief-based generation, contracts |
| **Labels and publishers** | Phase 3 | License catalogues and voices for revenue | Opt-in licensing marketplace |
| **Developers** | Phase 3 | Generate music in their own apps | Public API |

## 5. Scope by phase

Priority: P0 must ship in the phase, P1 should ship, P2 may ship. The Scrum Master and developers decide sprint-level order (DEC-010).

### 5.1 MVP (closed beta, target January 2027, A-003)

| ID | Feature | Priority | Replaces v1.0 | Notes |
| --- | --- | --- | --- | --- |
| FEAT-01 | Song from a description or vibe (mood, genre, occasion, feeling) | P0 | MVP-01 | Covers the founder's "vibe and feeling" request (SRC-005) |
| FEAT-02 | Custom mode: own lyrics, style, vocal type (male, female, duet), instrumental toggle, length | P0 | MVP-01 | |
| FEAT-03 | AI lyric writer in Hindi, English and Hinglish | P0 | new | Open-weight language model (ADR-007) |
| FEAT-04 | Beat and instrumental mode: genre, BPM, key, instruments to include or exclude | P0 | MVP-04 (partial) | Founder's "instruments and beat creation" |
| FEAT-05 | Two takes per request; regenerate; keep a take | P0 | MVP-02 | Draft-then-render only if the model's cost needs it (TDD 9.2) |
| FEAT-06 | Basic editor: extend, trim, fade, replace a section, compare takes | P1 | MVP-03 | Depends on model edit support (ACE-Step "repaint", A-020) |
| FEAT-07 | Downloads: MP3 for all; WAV and stems (separated) for paid plans | P0 | MVP-06, MVP-07 | Stems by source separation (Demucs, ADR-007) |
| FEAT-08 | Plans, purchasing-power prices, payments, credit wallet | P0 | MVP-16 | Section 9 |
| FEAT-09 | Rights: free commercial-use allowance (5 a month); paid ownership; licence certificate per song | P0 | MVP-08, MVP-11 | DEC-008, DEC-009 |
| FEAT-10 | AI labelling, C2PA credentials, watermark, provenance ledger, public verify page | P0 | MVP-09, MVP-10 | Compliance (RISK-007) |
| FEAT-11 | Creator licence certificate and claim dispute pack | P1 | MVP-11 | Certificate PDF plus a guide to dispute a claim |
| FEAT-12 | Library: projects, search, version history | P0 | MVP-13 | |
| FEAT-13 | Sharing: private link, public song page, embeddable player | P1 | MVP-14 | |
| FEAT-14 | Safety: prompt and lyric moderation, artist-name blocking, similarity screening, report abuse | P0 | MVP-15 | |
| FEAT-15 | Accounts: email, Google sign-in, phone OTP (India), age gate | P0 | MVP-16 | |
| FEAT-16 | Interface in English and Hindi; right-to-left ready | P0 | new | DEC-018 |
| FEAT-17 | Waitlist and invite system with daily generation caps | P0 | new | RISK-004, RISK-008 |
| FEAT-18 | Admin console: users, moderation queue, takedowns, plan overrides | P0 | new | Needed to run the beta |

### 5.2 Phase 2 (after public launch)

| ID | Feature | Priority | Notes |
| --- | --- | --- | --- |
| FEAT-20 | Mixer and DJ deck for songs the user made in BeatMind: crossfade, tempo match, loops, effects, export a mix | P0 | Founder's DJ mixer. Only BeatMind songs or uploads the user owns; third-party commercial songs need licences (out of scope) |
| FEAT-21 | "Inspired by" mode: describe or upload a reference for mood, tempo and structure only; similarity screening blocks close copies | P1 | Founder's "inspired by an existing song" request, limited to non-infringing attributes |
| FEAT-22 | Release to Spotify and other services through a distribution partner (ISRC, artwork, AI disclosure) | P1 | Founder's "publish to Spotify"; Spotify does not take uploads directly from apps like ours [A-034] |
| FEAT-23 | More Indian languages (Punjabi, Tamil, Telugu, Bengali, Marathi), each after it passes gate G1 | P0 | |
| FEAT-24 | Team workspaces, briefs, revisions, invoices for directors and agencies | P1 | |
| FEAT-25 | Melody input: hum or upload MIDI | P1 | |
| FEAT-26 | MIDI export and DAW-friendly stems | P1 | |
| FEAT-27 | Native mobile apps | P2 | Mobile web only before this (DEC-016) |

### 5.3 Phase 3

| ID | Feature | Notes |
| --- | --- | --- |
| FEAT-30 | Own-voice models with live identity verification | Removed from MVP (RISK-006) |
| FEAT-31 | Licensed artist voice and style marketplace with revenue share | Needs label deals |
| FEAT-32 | Public API and SDKs | |
| FEAT-33 | Model trained or fine-tuned on licensed data | After funding (RISK-001) |

## 6. Functional requirements (MVP)

Each requirement is testable; test cases arrive in Batch 2 (RTM).

### 6.1 Creation

- **REQ-F-01** The system must generate a song with vocals from a text description of up to 500 characters in under 60 seconds at p95 (beta target).
- **REQ-F-02** Custom mode must accept lyrics up to 3,000 characters in Hindi (Devanagari), Hinglish (Latin script) or English, a style description up to 500 characters, a vocal type and an instrumental toggle.
- **REQ-F-03** The lyric writer must produce lyrics with sections (verse, chorus, bridge) in the chosen language from a topic and mood.
- **REQ-F-04** Beat mode must accept genre, BPM (60 to 200), key and instruments to include or exclude, and produce an instrumental.
- **REQ-F-05** Each request must return two takes. The user can keep, regenerate or discard each.
- **REQ-F-06** Song length must be selectable from 30 seconds to 4 minutes in MVP.
- **REQ-F-07** If a language has not passed gate G1, the interface must mark it "experimental" or hide it.

### 6.2 Editing (P1)

- **REQ-F-10** The user can extend a song, trim it, fade in and out, and regenerate a selected section.
- **REQ-F-11** Every edit creates a new version; the user can compare and restore any earlier version.

### 6.3 Downloads, rights and certificates

- **REQ-F-20** Free users can download MP3 (192 kbps) for personal listening without limit, marked "Made with BeatMind AI".
- **REQ-F-21** Free users can mark up to 5 songs per calendar month for commercial use (A-005). Each gets a non-exclusive commercial licence certificate and requires visible attribution. BeatMind keeps ownership of free-plan songs.
- **REQ-F-22** Songs created while on a paid plan are assigned to the user (DEC-009): the certificate states that BeatMind transfers to the user all rights it holds in the output, and keeps only a licence to host, process and display it, plus training use if the user opts in.
- **REQ-F-23** Paid users can download WAV (48 kHz, 24-bit), MP3 (320 kbps) and up to 4 separated stems (vocals, drums, bass, other) without a monthly download cap. Account-level fair-use limits apply and are stated in the terms (A-033).
- **REQ-F-24** Upgrading does not retroactively transfer ownership of songs made on the free plan. A paid user may convert a free song with a one-time credit charge (rule to confirm in Batch 4).
- **REQ-F-25** Rights are fixed at creation and survive cancellation.
- **REQ-F-26** No screen, email or document may promise that a song is protected by copyright or free of all claims (RISK-011).

### 6.4 Labelling and provenance

- **REQ-F-30** Every exported file must carry an AI-generated label in its metadata, a C2PA manifest, and an inaudible watermark linked to the provenance ledger.
- **REQ-F-31** Every generation, edit and export must be written to an append-only provenance ledger, recording which inputs were human (lyrics, settings, edits) and which were AI.
- **REQ-F-32** The public verify page must report whether a file or certificate ID came from BeatMind, when, and which parts were AI-generated, without exposing private data.
- **REQ-F-33** Song pages and players must show a visible "AI-generated" label (India IT Rules, SRC-117).

### 6.5 Plans and payments

- **REQ-F-40** Prices must be shown in local currency according to the country's price band (section 9).
- **REQ-F-41** The billing country must be the payment method's country. If it differs from the browsing country, the billing country's price applies.
- **REQ-F-42** India must support UPI, cards and net banking. Other countries use cards through a merchant-of-record provider (ADR-009).
- **REQ-F-43** Users can cancel online in no more than three steps.

### 6.6 Safety and accounts

- **REQ-F-50** Prompts and lyrics naming real artists or asking for their voice or style must be blocked with an explanation.
- **REQ-F-51** Lyrics must be screened for hate, sexual content involving minors, and threats, in Hindi, Hinglish and English.
- **REQ-F-52** Generated audio must be checked against a fingerprint index of known recordings where available; close matches are withheld and logged (RISK-001).
- **REQ-F-53** Users must be 13 or older; commercial licences and paid ownership require 18 or older or verified parental consent where the law requires it (DPDP, RISK-014).
- **REQ-F-54** Takedown requests must be acknowledged within 24 hours and acted on within the legal deadline (3 hours for Indian government or court orders, SRC-117).
- **REQ-F-55** The beta must enforce per-account daily generation caps set by an admin.

## 7. Key user flows

1. **Vibe to video track (creator):** sign in with Google or phone, choose "For my video", type a mood ("chill Lo-fi for a Mumbai rain vlog, 60 seconds, no vocals"), get two takes, keep one, download MP3, mark it for commercial use (free) or download WAV and certificate (paid).
2. **Lyrics to song (artist):** paste Hindi lyrics or ask the lyric writer, choose style and voice, generate, compare takes, extend the best one, download.
3. **Beat (producer):** open Beat mode, set genre, BPM, key and instruments, generate, download stems.
4. **Upgrade:** a free user reaches the monthly commercial limit, sees the local price, pays by UPI, and new songs are owned by them.
5. **Verify:** a brand or platform uploads a file to the verify page and sees its BeatMind origin and AI disclosure.
6. **Takedown:** a rights holder files a claim; admin reviews; the song is disabled and the user notified.

Detailed screens and accessibility rules arrive in the UX specification (Batch 2).

## 8. Out of scope for MVP

- Voice cloning and any real artist's voice or style.
- Mixing, remixing or uploading third-party commercial songs the user does not own.
- Direct upload to Spotify (Phase 2 through a distributor).
- Native mobile apps, full DAW, social feed, public API.
- Legal advice, or any guarantee of copyright protection.
- Paid launch in Russia (RISK-012).

## 9. Plans and pricing principles

Full price tables and the financial model are Batch 4 deliverables. These principles are fixed now so engineering can build them.

| Plan | Who | Generation | Commercial use | Ownership | Downloads |
| --- | --- | --- | --- | --- | --- |
| Free | Everyone | Daily cap (beta: set by admin) | 5 songs a month, with attribution (A-005) | BeatMind owns; user gets a licence for the 5 songs | MP3 |
| Creator | Creators | Monthly credits | All songs | User owns | MP3, WAV, 4 stems |
| Pro | Artists and producers | More credits, priority queue | All songs | User owns | Plus higher limits, editing |
| Studio (Phase 2) | Directors and agencies | Team credits | All songs | Organisation owns | Plus team workspace, invoices |

**Pricing rules**

1. Prices come from a base US price multiplied by the country's band factor, then rounded to a local price point (market analysis section 5).
2. Starting hypothesis for bands: 1.0 (US, UK, EU, Australia, Japan), 0.6 (Mexico, Korea, Gulf), 0.35 (Brazil, Indonesia), 0.2 (India, Philippines, Nepal, Turkey), 0.12 (Pakistan, Bangladesh, Egypt, Nigeria). Tagged [H]; validated by price test H-004.
3. Annual plans at a discount; India-specific UPI autopay.
4. Credits cost must cover cost per song with margin; the financial model sets the minimum (TDD section 9).

## 10. Non-functional requirements

| ID | Area | Requirement |
| --- | --- | --- |
| REQ-NFR-01 | Latency | Song playable within 60 s at p95 in beta; target 20 s at p95 after funding |
| REQ-NFR-02 | Availability | Beta: 99.0% monthly for the web app and API; downloads of existing songs 99.5%. Post-funding target: 99.9% |
| REQ-NFR-03 | Scale | Architecture must scale horizontally to millions of registered users without redesign (DEC-011); beta sized for 1,000 to 5,000 invited users [E] |
| REQ-NFR-04 | Security | OWASP ASVS level 2 for the web app; encryption in transit and at rest; threat model in Batch 2 |
| REQ-NFR-05 | Privacy | Compliant with India's DPDP Act and Rules for Indian users; GDPR before serving EU users |
| REQ-NFR-06 | Audio quality | Output 44.1 or 48 kHz; no clipping; loudness normalised to about -14 LUFS for MP3 previews |
| REQ-NFR-07 | Cost | Average compute cost per generated song tracked per day; alert above the financial model's ceiling |
| REQ-NFR-08 | Accessibility | WCAG 2.2 level AA for all web pages (DEC-017) |
| REQ-NFR-09 | Localisation | Interface in English and Hindi at beta; all strings externalised; Unicode throughout; Indian numbering format where locale requires |
| REQ-NFR-10 | Platforms | Latest two versions of Chrome, Safari, Edge, Firefox; Android Chrome on 3 GB RAM phones over 4G |

## 11. Success metrics

Targets are set after the beta establishes baselines. Until then they are [H].

| ID | Metric | Definition | Beta goal [H] |
| --- | --- | --- | --- |
| METRIC-01 | Kept songs per week (north star) | Songs downloaded or shared by users | Baseline only |
| METRIC-02 | Keep rate | Takes kept divided by takes generated | 20% or higher |
| METRIC-03 | Free-to-paid conversion | New free users paying within 30 days | Baseline only |
| METRIC-04 | Language quality | Blind preference vs Suno and Lyria 3, per language | Parity or better on creator background music |
| METRIC-05 | Cost per kept song | GPU and API cost divided by kept songs | Below financial model ceiling |
| METRIC-06 | Similarity incidents | Confirmed infringement claims per 10,000 downloads | Zero tolerated without response within 24 h |
| METRIC-07 | Week-4 retention | Users active in week 4 after sign-up | Baseline only |

## 12. Release gates

| Gate | Criteria |
| --- | --- |
| G1 Model quality | Each launch language passes a blind listening test with at least 30 native listeners: BeatMind preferred or tied in at least 40% of pairings against the best competitor for creator background music. Licence files of every model archived (C-02). |
| G2 Beta readiness | All P0 features pass their tests; threat model mitigations for high risks done; labelling and provenance verified; admin takedown works end to end. |
| G3 Public launch | Payments live in India; compliance register signed off by counsel; terms published; 99.0% availability over the last 4 beta weeks; keep rate at or above 20%. |

## 13. Dependencies and assumptions

See [assumptions.csv](../registers/assumptions.csv) for A-001 to A-035 and [risks.csv](../registers/risks.csv) for RISK-001 to RISK-015. The most important for this document: A-001 (company in India), A-003 (January 2027 is a beta), A-005 (5 songs a month), A-021 (Hindi quality), A-033 (fair-use limits accepted).
