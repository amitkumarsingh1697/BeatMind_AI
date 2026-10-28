# Pitch deck v2 (slide script)

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 4), 2026-10-28; replaces the claims in the 20-slide deck of 2026-10-25 (SRC-004) |
| Rule | Every number carries its source. No customer, traction, revenue, valuation or round size is stated until it exists (DEC-012). Placeholders are marked [TO ADD]. |
| Use | Investor demo in January 2027 (A-003). Update slides 6, 10, 11 and 13 with beta evidence first. |

## Claims removed from the old deck and why

| Old claim (SRC-004) | Problem | Replacement |
| --- | --- | --- |
| "Certificate of ownership", "full ownership" | Overstates rights; AI output may not be copyrightable (BUG-010, BUG-041) | "Paid users receive every right BeatMind holds, with a licence and rights certificate" |
| "Training data under licence only" | Not true for open models (BUG-018, RISK-001) | Honest model and data slide (slide 8) |
| "Native stems incl. strings and FX" | Stems are separated, four parts (BUG-005, BUG-006) | "Four separated stems on paid plans" |
| "12 takes, 0 drifted" | Never measured (BUG-007) | Measured numbers from the beta only |
| "Content ID safelist" | No clearance mechanism (BUG-009, BUG-054) | "Licence certificate and dispute pack" |
| "Live identity check" | Voice cloning removed from MVP (BUG-017) | Removed |
| Market "$643M to $3B" | Source unknown; estimates disagree tenfold (market analysis 2.2) | Bottom-up market slide after beta |
| Suno "2M paying, $300M ARR, $5.4B valuation" | Unverified secondary figures | Only cite dated, sourced competitor facts |

## Slides

**1. Title.** BeatMind AI. Original songs in your language, at your price, that you own. [Logo] [Founders' names] [Date]

**2. Problem.** Creators in India need original music for videos every week. Today's AI music tools:
- cost Indians about the US price: Suno Pro is ₹999 a month, five times Spotify Premium's ₹199 (SRC-111, SRC-116, October 2026);
- do not speak Hindi natively, except Google's Lyria 3 (SRC-112, July 2026);
- lock songs in or blur ownership: Udio does not allow downloads (SRC-101, April 2026).

**3. Solution.** Type a vibe or paste your lyrics in Hindi, Hinglish or English. Get two takes in about a minute. Keep one. Download it. On a paid plan, it is yours. Every song is labelled as AI-made and carries a certificate.

**4. Demo.** [Live demo or 60-second recording from the beta: vibe to video track; Hindi lyrics to song; certificate and verify page.]

**5. Why now.**
- India has 518 million YouTube users, the largest YouTube audience (SRC-114, April 2026).
- India now requires AI audio to be labelled with provenance markers (IT Rules, in force 20 February 2026, SRC-117); the EU since 2 August 2026 (SRC-119). Provenance is built into BeatMind from day one.
- Open music models with commercial licences became usable in 2026 (ACE-Step 1.5, February 2026, SRC-106), cutting generation cost to under a cent a song [C, TDD 9.1].

**6. Market.** Bottom-up serviceable market for India creators (market analysis section 7). [TO ADD after EXP-01 and EXP-02: creator counts, measured conversion, local price.] No top-down market figure is shown until a single reputable source is chosen.

**7. Product and what makes it different.**

| | BeatMind | Suno | Udio | Google Lyria 3 |
| --- | --- | --- | --- | --- |
| India price | ₹149 and ₹349 [H] | ₹999 and ₹2,999 | Not checked | Not reported |
| Native Hindi | Gate G1 [TO ADD result] | No (Hinglish works) | Not reported | Yes |
| Download and own paid songs | Yes | Downloads yes; ownership terms unclear | No downloads | Not reported |
| AI label and certificate on every song | Yes | Not reported | Not reported | SynthID watermark |

Sources and dates in the market analysis, section 3.

**8. Technology and model strategy.** Modular Java core with Python GPU workers; open-weight models with commercial licences (ACE-Step 1.5 primary), Lyria API as a capped fallback; provenance ledger, C2PA credentials and watermark. Honest risk: the model publishers' training-data statements are not independently verified; BeatMind screens outputs for similarity and plans licensed regional data after funding (RISK-001).

**9. Business model.** Subscriptions with purchasing-power pricing in five bands. Unit economics from the financial model: contribution margin about 61% (India Creator) to 92% (US Creator) at the modelled GPU cost [C, hypotheses]. Free tier capped; 5 commercial songs a month with attribution drives word of mouth.

**10. Traction.** [TO ADD only from the evidence log: waitlist size, interviews, G1 results, beta keep rate, retention.] Until then this slide says "Beta started [date]; first results [date]".

**11. Go-to-market.** India first, creators first; founding-creator programme; Shorts and Reels content; SEO; college and creator communities; expansion to South Asia, Indonesia and Brazil (GTM plan).

**12. Competition and risks.** Google is the main threat in India; open-model quality may trail Suno; training-data provenance; free-tier cost. Each has a mitigation in the risk register.

**13. Team.** [TO ADD: three senior developers and founder, roles, relevant experience.] Gaps we will hire for: audio-ML engineer, rights and partnerships lead.

**14. Roadmap.** Closed beta January 2027; paid launch in India after gate G3; Phase 2: mixer and DJ deck for own songs, release to Spotify through a distributor, more Indian languages; Phase 3: licensed artist voices and API.

**15. The ask.** [TO ADD by founders: amount and instrument.] Use of funds by share: product and engineering 40%, compute 20%, licensed data and rights 15%, go-to-market 15%, legal, compliance and contingency 10% (financial model 4.2).

**Appendix.** Sources list; financial model summary; compliance summary; security summary.
