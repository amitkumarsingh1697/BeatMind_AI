# Validation plan

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 3), 2026-10-28 |
| Perspective | Product discovery and research lead |
| Starting point | No customer evidence of any kind (DEC-012) |
| Goal | Replace hypotheses with evidence before the beta, before paid launch and before fundraising |
| Status | Draft. No experiment has run yet. |

## 1. What must be true for BeatMind to work

| ID | Hypothesis | Risk if false | Experiment | Pass mark | When |
| --- | --- | --- | --- | --- | --- |
| H-001 | Indian short-form and YouTube creators will pay a local-price subscription for claim-safe original music | No business in the beachhead | EXP-01 interviews; EXP-02 waitlist | At least 12 of 20 interviewees describe a current music pain they spend time or money on; at least 500 waitlist sign-ups in 6 weeks [H] | Sprint 0 to 2 |
| H-002 | Open models make Hindi and Hinglish songs good enough for creator background music | Beachhead language strategy fails (RISK-003) | EXP-03 G1 listening test | PRD gate G1 | Sprint 0 spike; Sprint 3 full test |
| H-003 | Creators value ownership and downloads over top quality | Positioning fails against Google and Suno (RISK-002) | EXP-01 forced-choice question; EXP-04 concept test | At least 60% choose "own and download, good quality" over "best quality, cannot download" [H] | Sprint 1 |
| H-004 | About 0.2 of the US price in India converts better than Suno's ₹999 while covering costs | Revenue per user too low (RISK-013) | EXP-02 price variants; EXP-06 beta conversion | Variant A sign-up rate not more than 30% below variant B; contribution margin positive in the financial model | Sprint 0 to beta |
| H-005 | Independent artists will demo their own lyrics in regional languages | Second persona weak | EXP-01 artist interviews; beta usage of custom lyrics | At least 25% of beta songs use custom lyrics [H] | Beta |
| H-006 | Free users will accept attribution on the 5 commercial songs | Free tier fails to grow the brand | EXP-04 | At least 50% find attribution acceptable [H] | Sprint 1 |
| H-007 | Users understand who owns their song | Legal and trust risk (RISK-011) | EXP-05 licence panel comprehension | At least 80% answer correctly after reading the panel | Sprint 2 |
| H-008 | Keep rate reaches 20% | Cost per kept song too high | EXP-06 beta analytics (METRIC-02) | 20% or higher by beta week 4 | Beta |

Pass marks tagged [H] are starting proposals; the founder can change them before the experiment starts, never after.

## 2. Experiments

### EXP-01 Creator and artist interviews

- **Who:** 20 people: 12 short-form or YouTube creators (mix of Hindi and English content, 1k to 500k subscribers), 5 independent artists or lyricists, 3 producers. Recruited from the founders' networks, creator communities and the waitlist.
- **How:** 30-minute remote interviews, recorded with consent, in Hindi or English.
- **Script (core questions):**
  1. Tell me about the last video where you needed music. Where did you get it? What did it cost in time and money?
  2. Have you had a copyright claim or a muted video? What happened?
  3. Have you used any AI music tool? What did you like and dislike?
  4. If you could only have one: the best possible quality but you cannot download or own the song, or good quality and you own and download it?
  5. What do you pay for each month for your content work? (Show the local price after this question, not before.)
  6. Would attribution like "Music: BeatMind AI" in your description be acceptable for free songs?
- **Output:** notes per interview in `docs/11-validation/interviews/` (no personal data beyond a pseudonym), synthesis against H-001, H-003, H-005, H-006.

### EXP-02 Waitlist and price test

- Landing page in English and Hindi (US-007) describing the product honestly as "coming soon".
- Two price variants shown at random to Indian visitors: A at the 0.2 band, B at Suno-like pricing. No payment taken.
- Measures: visit-to-sign-up rate per variant; source channel; stated use case.
- Ethics: no fake checkout; consent for contact; delete data on request.

### EXP-03 G1 listening test

- 30 or more native listeners per language (Hindi, Hinglish, English), paid a small honorarium if budget allows.
- 20 prompts per language covering creator use cases; each prompt produces one BeatMind take and one take from the best available competitor.
- Blind pairwise preference in a web form; order randomised; listeners also rate pronunciation from 1 to 5.
- Pass mark from PRD gate G1. Results and raw data stored in the repository.
- Competitor outputs are used only for internal evaluation and deleted after the test; terms of each competitor checked first.

### EXP-04 Concept test

- Short survey to waitlist sign-ups with three concept cards (own and download; best quality; cheapest) and the attribution question.

### EXP-05 Licence panel comprehension

- Five-second and comprehension tests with 10 users on the licence panel designs (UX spec section 3).

### EXP-06 Beta analytics

- Funnel: invite accepted, first song, kept take, download, commercial licence, return in week 2 and week 4.
- METRIC-01 to METRIC-07 from the PRD with consented analytics (DPIA section 2).

## 3. Decision rules

| Result | Decision |
| --- | --- |
| H-002 fails for Hindi | Launch Hindi only through the Lyria fallback if cost allows, or delay Hindi and lead with Hinglish and English |
| H-001 fails (under 8 of 20 show the pain) | Re-test with independent artists as the primary persona before building payments |
| H-004 variant A converts much better | Keep the 0.2 band for India; test 0.12 for Wave 2 markets |
| H-007 fails | Rewrite the licence panel and certificate; retest before paid launch |
| H-008 under 10% | Pause paid launch; improve model or prompts first |

## 4. Evidence log

Every finished experiment adds a row to [evidence-log.csv](evidence-log.csv) with the date, sample, result, and the hypothesis status (supported, weakened, refuted). Investor material may only cite rows in this log as customer evidence.
