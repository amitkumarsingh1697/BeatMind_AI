# Pricing and financial model

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 4), 2026-10-28 |
| Perspective | Chartered accountant and finance lead, as requested (DEC-007) |
| Workbook | [financial-model.xlsx](financial-model.xlsx), built by [build_financial_model.py](build_financial_model.py). All outputs are formulas; yellow cells are inputs. |
| Status | Draft for founder review. **Not tax or investment advice.** A practising chartered accountant must review the tax lines (GST, income tax, transfer pricing if any) before launch. |

Figures below were calculated by the workbook with its default inputs, recalculated on 2026-10-28. Every input is tagged; none is measured yet.

## 1. Recommended prices

Prices are set by country band (DEC-013, market analysis section 5). Band factors are hypotheses to test in EXP-02.

| Band | Factor | Example countries | Creator per month | Pro per month |
| --- | --- | --- | --- | --- |
| B1 | 1.0 | US, UK, EU, Australia, Japan | US$8 | US$18 |
| B2 | 0.6 | Mexico, South Korea, Gulf | US$4.80 equivalent | US$10.80 equivalent |
| B3 | 0.35 | Brazil, Indonesia | US$2.80 equivalent | US$6.30 equivalent |
| **B4** | **0.2** | **India**, Philippines, Nepal, Turkey | **₹149 incl. GST** | **₹349 incl. GST** |
| B5 | 0.12 | Pakistan, Bangladesh, Egypt, Nigeria | US$0.96 equivalent | US$2.16 equivalent |

Why these numbers:

- The B1 prices sit below Suno's US$10 and US$30 because open models are unlikely to match Suno's quality at launch (RISK-002).
- The formula price for India is ₹141 and ₹317 [C: base x 0.2 x ₹88 per US$, A]. The chosen price points round to ₹149 and ₹349.
- ₹149 is below Spotify Premium India at ₹199 [ES, SRC-116]; ₹349 is about a third of Suno Pro India at ₹999 [ES, SRC-111].
- Local price points per country are set in local currency and reviewed quarterly, not converted live.
- Annual plans: 2 months free (about 17% discount) [H].

Allowances: Creator 300 takes a month, Pro 800 takes a month [H]. Users are expected to use about 35% of their allowance [H].

## 2. Unit economics per paying user per month

| Line (US$) | India Creator | India Pro | B1 Creator | B1 Pro |
| --- | --- | --- | --- | --- |
| Price after GST | 1.44 | 3.36 | 8.00 | 18.00 |
| Payment fees | 0.03 | 0.07 | 0.90 | 1.40 |
| Net revenue | 1.41 | 3.29 | 7.10 | 16.60 |
| Compute (takes used x US$0.004) | 0.42 | 1.12 | 0.42 | 1.12 |
| Storage and delivery | 0.13 | 0.34 | 0.13 | 0.34 |
| **Contribution** | **0.86** | **1.84** | **6.55** | **15.14** |
| **Contribution margin** | **61%** | **56%** | **92%** | **91%** |

All values [C] from the workbook inputs. B1 prices exclude sales tax, which the merchant of record adds and remits.

Key findings:

1. **India works only on self-hosted models.** If more than about 9 to 11% of Indian paid takes go to the Lyria API fallback, Indian plans lose money [C: break-even fallback share 10.8% for Creator, 8.6% for Pro]. Recommendation: fallback off for India at launch; use it only for languages that fail G1, and mainly on B1 plans, which stay profitable with up to 71% (Pro) to 82% (Creator) of takes on the fallback [C].
2. **A free user costs about US$0.08 a month** in compute [C: 20 takes x US$0.004]. Free caps matter more than any other cost lever.
3. **GPU cost per take is the most important unknown** (A-025, A-026). At US$0.006 instead of US$0.004, Indian Pro margin falls to about 40% [C]. Sprint 0 benchmarking (US-005) resolves this.

## 3. Twelve-month scenarios (India price points)

These are planning scenarios from hypothetical inputs, **not forecasts and not traction**. They exclude fixed costs, salaries and taxes on profit.

| Scenario input [H] | Low | Base | High |
| --- | --- | --- | --- |
| New free sign-ups in month 1 | 500 | 2,000 | 6,000 |
| Monthly growth in new sign-ups | 5% | 15% | 25% |
| Monthly conversion of active free users | 1% | 2.5% | 5% |
| Share of paid users on Pro | 20% | 30% | 40% |

| Month-12 output [C] | Low | Base | High |
| --- | --- | --- | --- |
| Active free users | about 2,000 | about 19,400 | about 134,300 |
| Active paid users | about 120 | about 2,200 | about 24,500 |
| Net revenue in month 12 (US$) | about 215 | about 4,340 | about 52,900 |
| Free-tier compute in month 12 (US$) | about 160 | about 1,560 | about 10,740 |
| Cumulative contribution after free-tier cost, months 1 to 12 (US$) | about -590 | about 3,270 | about 77,130 |

Reading: in the low scenario, free users cost more than paid users bring in, so the free tier must stay capped and invite-only until conversion is measured. The base scenario covers its own compute but not salaries; BeatMind needs outside funding or founder time without pay to reach scale either way.

## 4. Budget recommendations

### 4.1 Before funding (₹5,000 to ₹10,000 a month, DEC-023)

| Use | ₹5,000 a month | ₹10,000 a month | Notes |
| --- | --- | --- | --- |
| GPU compute for beta (rented by the hour, scaled to zero) | 60% (₹3,000) | 60% (₹6,000) | Largest cost. Startup credits are applied first; unspent money rolls into the next month |
| Domain, email, tools beyond free tiers | 10% (₹500) | 10% (₹1,000) | Domain about once a year |
| Listening-test honoraria and interview incentives | 10% (₹500) | 15% (₹1,500) | Evidence for gate G1 and for investors |
| Company compliance (accountant, filings) | 20% (₹1,000) | 15% (₹1,500) | Incorporation itself is a one-off cost; get quotes |
| Counsel review of terms | 0% | 0% | Paid one-off from savings before paid launch, or after funding |

At ₹3,000 to ₹6,000 a month for GPU, the beta can run on hourly rented GPUs that switch off when idle. At the modelled cost of $0.002 to $0.006 a take (TDD 9.1, not yet measured), that is about 5,700 to 17,000 takes a month [C: ₹3,000 ÷ 88 ≈ $34; $34 ÷ $0.006 to $0.002], enough for an invite-only beta. The Sprint 0 benchmark (US-005) replaces this estimate. The fallback API (Lyria, about $0.08 a song) stays off during the beta unless credits cover it. Google's programme lists a working MVP as a requirement for its Start tier [SS, SRC-110], so credits are likely to arrive only after the January beta.

### 4.2 After a seed round (share of the round; no amount assumed)

| Use of funds | Share | What it buys |
| --- | --- | --- |
| Product and engineering | 40% | Audio-ML engineer, second backend engineer, designer |
| Compute | 20% | Lower latency, higher free caps, paid-cloud migration |
| Licensed data and rights | 15% | Regional catalogue and commissioned sessions to retire RISK-001 |
| Go-to-market | 15% | Creator partnerships, content, paid tests in India |
| Legal, compliance, finance and contingency | 10% | Counsel, audits, insurance, buffer |

The founders decide the round size. The model's scenario sheet can be extended with salaries and fixed costs to calculate runway once they do.

## 5. Finance set-up checklist (India, A-001)

| Item | Why | Status |
| --- | --- | --- |
| Incorporate a private limited company | Contracts, Razorpay, investors (COMP-IN-10) | Not started |
| PAN, TAN, GST registration | Tax compliance (COMP-IN-07) | Not started |
| Current account with a bank | Payment settlement | Not started |
| Startup India (DPIIT) recognition | Possible tax and compliance benefits [A] | Not started |
| Founders' agreement and IP assignment to the company | Investors will require it (diligence pack) | Not started |
| Accounting software and monthly close | Clean books for diligence | Not started |
| Cap table record | Fundraising | Not started |

## 6. Inputs to replace first

| Input | Replace with | When |
| --- | --- | --- |
| GPU cost per take | Sprint 0 benchmark (US-005) | SPRINT-00 |
| Exchange rate, GST, fees | Current figures from RBI, the tax adviser and providers | Before paid launch |
| Conversion, churn, usage | Beta data (EXP-06) | Beta weeks 4 to 8 |
| Price bands | Waitlist price test (EXP-02) | SPRINT-02 |
