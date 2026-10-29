# Market, launch-market and competitor analysis

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 1) |
| Date | 2026-10-28 |
| Perspective | Market analyst and business analyst, as requested in DEC-002 |
| Status | Draft for founder review |
| Evidence tags | See [package README](../README.md#evidence-classification). Source IDs refer to [sources.csv](../registers/sources.csv). |

## 1. Summary

1. **Recommended beachhead: India**, with the English product open worldwide from day one. India scores highest in the launch-market matrix (section 4): the largest YouTube audience, a clear price gap, a clear language gap, and (assuming founders are in India, A-001) local payment rails and founder proximity.
2. **The sharpest gap is price.** Suno charges ₹999 a month for Pro in India, about the same in US dollars as its US price [ES, SRC-111]. Spotify Premium in India costs ₹199 [ES, SRC-116]. Suno's entry plan therefore costs about 5 times a local music subscription [C: 999 / 199 = 5.0]. Purchasing-power pricing (DEC-013) attacks this directly.
3. **The second gap is language.** A July 2026 Indian review reports that Suno and Udio do not offer native Hindi prompts, while Google's Lyria 3 (inside Gemini) does [ES, SRC-112]. Google is therefore the main threat in India, not Suno.
4. **Incumbents are moving to licensed, closed models.** Udio signed Universal, Warner, Merlin and Kobalt and moved to a walled garden where songs cannot be downloaded [ES, SRC-101]. Suno signed Warner and still allows downloads [ES, SRC-101]. BeatMind's promise (users own and download their paid songs, DEC-009) is attractive, but BeatMind has no licensed data (DEC-005). That legal risk is the main weakness to manage (RISK-001).
5. **Rules now require labelling of AI audio.** India's amended IT Rules (in force 20 February 2026) cover audio and require prominent labels plus permanent provenance metadata [ES, SRC-117]. EU AI Act Article 50 applies from 2 August 2026, with machine-readable marking of AI audio and a runway to 2 December 2026 for the watermarking part [ES, SRC-119]. BeatMind's provenance design (TDD section 6) is a compliance requirement, not only a differentiator.
6. **Do not use top-down market-size figures yet.** Published AI-music market estimates disagree widely and several are unattributed (section 2.2). The pitch deck's "$643M to $3B" figure is replaced by a bottom-up model in Batch 4.

## 2. Market context

### 2.1 Recorded music

| Fact | Value | Tag |
| --- | --- | --- |
| Global recorded music revenue, 2025 | US$31.7 billion, up 6.4% | [ES, SRC-100] |
| Paid streaming subscription accounts, 2025 | 837 million | [ES, SRC-100] |
| Fastest-growing regions, 2025 | Latin America +17.1%; Middle East and North Africa +15.2%; Sub-Saharan Africa +15.2%; Asia +10.9%; USA and Canada +3.5% | [ES, SRC-100] |
| Top 10 markets, 2025 | USA, Japan, UK, China, Germany, France, South Korea, Brazil, Canada, Mexico | [ES, SRC-100] |
| India rank in IFPI top 10 | Not in the top 10. Exact rank not found in the source read. | [U] |

### 2.2 AI music market size

| Estimate | Source | Problem |
| --- | --- | --- |
| US$643M growing to US$3B (used in pitch deck SRC-004) | Not identified in the deck | Source unknown |
| US$6.65B in 2025, US$60.44B by 2034 | Dynamoi [SRC-103] | No original source named for the totals |
| US$2.92B generative AI segment, 2025 | market.us via Dynamoi [SRC-103] | Secondary citation |

These figures differ by a factor of about 10 and cannot be reconciled. **Recommendation:** investor material uses a bottom-up serviceable market built from measured inputs (section 6), with at most one top-down figure from a named, paid analyst report if the founders buy one. Until then the market size is [U].

### 2.3 India demand signals

| Fact | Value | Tag |
| --- | --- | --- |
| YouTube users in India, April 2026 | 518 million, the largest YouTube market | [ES, SRC-114 citing Statista] |
| YouTube users in the US, late 2025 | 254 million | [ES, SRC-114 citing DataReportal] |
| Paid music subscriptions in India, end 2025 | About 14 million | [ES, SRC-109] |
| Projected paid music subscriptions in India, 2028 | 28 to 30 million | [ES, SRC-109] |
| Indian smartphone users who listen to music | 96% | [ES, SRC-109] |
| Indian respondents who have ever paid for music streaming | 38% | [ES, SRC-109] |
| Would pay if free options ended and prices were reasonable | 61% | [ES, SRC-109] |
| Number of Indian YouTube creators who need background or original music | Not found | [U] |

Reading: India has a huge listening and creator audience but low paid penetration. A creator tool must be priced like a local subscription and must sell an outcome creators already value (claim-safe music for videos), not "AI music" in the abstract. This is a hypothesis to test (H-001 in section 6).

## 3. Competitor analysis (October 2026)

This updates the competitor tables in SRC-001 with 2026 events. Capability claims are as reported by the sources; none were tested hands-on.

| Competitor | 2026 position | Pricing signal | India relevance | Threat to BeatMind | Tag |
| --- | --- | --- | --- | --- | --- |
| **Suno** | Market leader. v5.5 released 27 March 2026 with voice capture and personalisation. Warner Music deal (November 2025); no Universal deal; still allows downloads. | Pro ₹999 and Premier ₹2,999 a month in India; $10 and $30 in the US | Handles Hinglish prompts; no native Hindi reported | High globally, medium in India on price | [ES, SRC-101, SRC-111, SRC-112] |
| **Udio** | Licensed by Universal (Oct 2025), Warner (Nov 2025), Merlin (Jan 2026), Kobalt (Apr 2026). Walled garden: songs cannot be downloaded or distributed outside the platform. | Not checked | Not reported | Low for creators who need downloads; high for licensing credibility | [ES, SRC-101] |
| **Google (Lyria 3, Gemini, ProducerAI in Labs)** | Lyria 3 inside Gemini; ProducerAI moved into Google Labs (February 2026) as a full music studio with artwork and video. SynthID watermark on all outputs. Lyria 3 Pro API about US$0.08 a song. | API about $0.08 a song; consumer pricing not reported | Native Hindi prompts reported | **Highest in India**: distribution through Gemini and YouTube | [ES, SRC-112, SRC-113, SRC-115] |
| **ElevenLabs Music** | API at about US$0.15 a minute; refuses prompts naming copyrighted works or artists | API per minute | Not reported | Medium (quality and brand) | [ES, SRC-115] |
| **Stability AI (Stable Audio 3.0)** | Released 20 May 2026. Small and Medium models have open weights; Large is API only. Stability says all models are trained on licensed data. Free for organisations under US$1M annual revenue. | Community licence | Not reported | Low as a competitor; useful as a component (ADR-007) | [ES, SRC-108] |
| **MiniMax, Mureka** | Music APIs with vocals; about US$0.15 a track (MiniMax), from US$1,000 a month (Mureka official) | API | Not reported | Low to medium | [ES, SRC-115] |
| **Beatoven.ai** | Listed among the top tools for Indian musicians in 2026 | Not checked | Reported to be India-based; not confirmed in the source read | Medium in India for background music | [ES, SRC-112]; origin [U] |
| **Soundraw, Boomy, AIVA** | Covered in SRC-001 (October 2026). No 2026 changes checked. | See SRC-001 | Not reported | Low | [ES, SRC-001] |

### 3.1 Where BeatMind can win

| Gap | Evidence | BeatMind response | Defensibility |
| --- | --- | --- | --- |
| Price is not adjusted to local purchasing power | Suno India price about equals US price [ES, SRC-111] | Region-based purchasing-power pricing (DEC-013) | Low on its own (copyable), high combined with low inference cost from open models |
| Indian languages | No native Hindi in Suno or Udio [ES, SRC-112] | Hindi, English and Hinglish first; other Indian languages tested per model (A-021) | Medium. Google already supports Hindi. |
| Downloads and ownership | Udio walled garden; Suno ownership language unclear [ES, SRC-101, SRC-001] | Paid users own and download their songs (DEC-009) | Medium. Depends on managing training-data risk. |
| Claim-safe music for creators | Content ID claims are a known creator pain [ES, SRC-001] | Licence certificate per song and a dispute pack (PRD FEAT-11) | Medium |
| Mandatory AI labelling | India IT Rules 2026, EU Article 50 [ES, SRC-117, SRC-119] | Labels, C2PA credentials and watermark by default | Medium. Turns compliance into a trust feature. |

### 3.2 Where BeatMind should not compete in Phase 1

- **Raw quality against Suno v5.5 or Google Lyria 3.** Open-weight models are unlikely to match them at launch [H]. BeatMind should compete on price, language, ownership and creator workflow.
- **Celebrity or label voices.** No licences exist (DEC-005).
- **A full browser DAW.** Three developers cannot build one alongside the core product.

## 4. Launch-market selection

### 4.1 Method

Each candidate market is scored from 1 (poor) to 5 (strong) on seven weighted criteria. Scores are analyst judgements [E] based on the evidence cited. Change a weight or score and the ranking can be recalculated in the XLSX workbook.

| Criterion | Weight | What it measures |
| --- | --- | --- |
| Reachable creator audience | 20% | YouTube and short-form audience, creator density |
| Ability to pay at local prices | 15% | Paid subscription penetration, purchasing power |
| Competitive gap | 20% | Missing local language support, unadjusted competitor pricing |
| Model language fit | 10% | Likelihood that open models handle the main language well |
| Regulatory clarity and burden | 10% | AI labelling, data protection, copyright clarity |
| Payments and distribution rails | 10% | Ability to collect money and pay out |
| Founder proximity | 15% | Language, network and time-zone advantage of the team (A-001) |

### 4.2 Scores

| Market | Audience | Pay | Gap | Language | Regulation | Payments | Founders | **Weighted score** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **India** | 5 | 2 | 5 | 3 | 3 | 4 | 5 | **4.05** |
| United States | 4 | 5 | 2 | 5 | 3 | 4 | 1 | 3.30 |
| Brazil | 4 | 3 | 3 | 3 | 3 | 3 | 1 | 2.90 |
| France and EU | 3 | 5 | 2 | 4 | 2 | 4 | 1 | 2.90 |
| Indonesia | 4 | 2 | 4 | 2 | 3 | 3 | 1 | 2.85 |
| Pakistan, Bangladesh, Nepal | 3 | 1 | 5 | 2 | 2 | 1 | 2 | 2.55 |
| Russia | 3 | 2 | 3 | 4 | 1 | 1 | 1 | 2.25 |

Weighted scores are [C] from the scores and weights above.

Key reasons:

- **India:** 518 million YouTube users [ES, SRC-114]; Suno priced at about 5 times local music subscriptions [C]; no native Hindi in Suno or Udio [ES, SRC-112]; UPI and Indian gateways available to an Indian entity [A-001]. Weak spot: only 38% have ever paid for music streaming [ES, SRC-109].
- **United States:** highest ability to pay, but the most crowded market and the home market of every major competitor.
- **France and EU:** strong ability to pay, but Article 50 marking duties and GDPR apply on day one [ES, SRC-119]. Serve EU users only after the watermark and labelling work passes testing (C-06).
- **Pakistan, Bangladesh, Nepal:** large language gap and cultural overlap with India, but weak card payments. Stripe is not available in Pakistan [A-022, to confirm in C-04].
- **Russia:** good Russian-language demand, but international card payments and many Western cloud services are unavailable because of sanctions [A-023]. **Recommendation:** support the Russian language for users anywhere, but do not run a paid launch in Russia (RISK-012).

### 4.3 Recommendation and sequence

| Wave | Markets | Languages | Condition to start |
| --- | --- | --- | --- |
| Beta (Jan 2027, A-003) | India, invite-only; English open worldwide on the waitlist | Hindi, English, Hinglish | Model passes language quality gate G1 (TDD section 11) |
| Wave 1 (public launch) | India and global English | Add Punjabi, Tamil, Telugu, Bengali, Marathi if they pass G1 | Payments live; compliance register signed off |
| Wave 2 | Pakistan, Bangladesh, Nepal, Sri Lanka; Indonesia; Brazil and Latin America | Urdu, Bengali, Nepali, Indonesian, Portuguese, Spanish | Local payment methods confirmed (C-04) |
| Wave 3 | EU, UK, US paid marketing | French, German, Spanish, Russian (outside Russia), Japanese, Korean | Article 50 marking verified (C-06); funding for paid acquisition |

### 4.4 Beachhead customer within India

The founder named artists, YouTube and content creators, the music industry, producers and directors (DEC-003). For a three-person team, the beachhead is narrowed (DEC-102):

1. **Short-form and YouTube creators** (first): need many original, claim-safe tracks; tolerate imperfect quality; large volume.
2. **Independent artists and lyricists** (second): want Hindi and regional-language demos of their own lyrics.
3. **Producers and beat makers** (third): want beats, loops and stems; most demanding on quality.
4. **Directors, ad agencies and the wider industry** (Phase 2): need briefs, revisions, contracts and invoices; a sales-led motion the team cannot staff yet.

## 5. Pricing signals for purchasing-power pricing

This section gives the evidence for DEC-013. The price table itself is a Batch 4 deliverable.

Spotify Premium Individual prices act as an observed local price anchor because Spotify already sets prices by country. AUD equivalents are from the source; the ratio to the US price is [C].

| Country | Spotify local price | AUD equivalent | Ratio to US |
| --- | --- | --- | --- |
| United States | US$11.99 | 17.08 | 1.00 |
| United Kingdom | £12.99 | 24.70 | 1.45 |
| Germany | €12.99 | 20.69 | 1.21 |
| South Korea | ₩10,900 | 11.72 | 0.69 |
| Mexico | MX$139 | 10.65 | 0.62 |
| Japan | ¥1,080 | 9.77 | 0.57 |
| Indonesia | Rp79,900 | 6.26 | 0.37 |
| Brazil | R$23.90 | 6.27 | 0.37 |
| Philippines | ₱169 | 3.77 | 0.22 |
| Nepal | US$3.29 | 4.69 | 0.27 |
| **India** | **₹199** | **2.98** | **0.17** |
| Turkey | ₺99 | 2.83 | 0.17 |
| Bangladesh | ৳219 | 2.52 | 0.15 |
| Pakistan | Rs 349 | 1.79 | 0.10 |
| Egypt | E£69.99 | 1.77 | 0.10 |
| Nigeria | ₦1,600 | 1.68 | 0.10 |

Source: SRC-116 (prices cited as 2025 and June 2026). Exchange rates move; the production system will use a maintained price table per country, reviewed quarterly, not live conversion.

**Principle for Batch 4:** BeatMind prices in a small number of price bands (for example 1.0, 0.6, 0.35, 0.2 and 0.12 of the US price), assigns each country to a band using a published price-level index (World Bank price level ratio, indicator PA.NUS.PPPC.RF) cross-checked against Spotify's local price, and rounds to local price points. VPN abuse is handled by pricing on the payment method's country, not only the IP address (REQ-F-41).

## 6. Hypotheses to validate

None of these has evidence yet (DEC-012). The validation plan (Batch 3) turns each into an experiment with a pass mark.

| ID | Hypothesis | First test |
| --- | --- | --- |
| H-001 | Indian YouTube and short-form creators will pay a local-price subscription for claim-safe original music. | 20 creator interviews; waitlist landing page with price shown |
| H-002 | Hindi and Hinglish generation from open models is good enough for creator background music. | Blind listening test with 30 Indian listeners against Suno and Lyria 3 (gate G1) |
| H-003 | Creators value ownership and downloads over raw quality. | Conjoint or forced-choice survey in interviews |
| H-004 | Price at about 0.2 of the US price in India converts better than Suno's ₹999 without losing margin. | Price test on the waitlist page; unit-cost check in the financial model |
| H-005 | Independent artists will use BeatMind to demo their own lyrics in regional languages. | 10 artist interviews; beta usage of the custom-lyrics flow |

## 7. Bottom-up market model (structure only)

The founders asked us not to invent market figures, so this section defines the model and its inputs. Numbers are filled from measured or sourced data in Batch 4.

```
Serviceable market (India, year 1) =
    Indian creators who publish music-backed videos at least monthly     [U, needs source]
  x share reachable through our channels in year 1                       [A, set in GTM]
  x conversion from free to paid                                         [H-004, from beta]
  x average revenue per paying user per year (local price bands)         [C, from price table]
```

## 8. Risks raised by this analysis

| Risk | Summary | Register |
| --- | --- | --- |
| RISK-001 | Open models' training data cannot be verified; users and BeatMind could face infringement claims. | risks.csv |
| RISK-002 | Google's Lyria 3 already supports Hindi and reaches users through Gemini and YouTube. | risks.csv |
| RISK-003 | Open models may not reach acceptable quality in Hindi and regional languages. | risks.csv |
| RISK-012 | Russian-language demand cannot be monetised inside Russia. | risks.csv |
| RISK-013 | Low willingness to pay in India keeps revenue per user low. | risks.csv |
