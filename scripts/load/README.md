# Load test and security baseline (US-042, gate G2)

Gate G2 needs the load test to meet REQ-NFR-01 at twice the expected beta peak, and a security
baseline scan with every finding triaged (testing strategy, section 6).

## Load test (k6)

`k6-beta.js` plays one listener per virtual user: sign in, open the library, ask for a song (one in
four is a beat), follow it until the takes are ready, play take 1, then look at the song and
sometimes share it. It ramps to `PEAK`, then to `2 × PEAK`, and holds there for `HOLD` minutes.

**Peak assumption.** The beta is sized for 1,000 to 5,000 invited people (REQ-NFR-03). Taking the
top of that range, and 1% of them making songs at the busiest moment, the peak is 50 at once, so
the test runs at 100. This is an estimate [E]: change `PEAK` once the first beta week shows the real
number, because the result only counts at twice the real peak.

**Pass bar.**

| Threshold | Limit | Source |
|---|---|---|
| `song_playable_seconds` p95 | under 60 s | REQ-NFR-01 (beta) |
| `song_failed` rate | under 2% | keep rate budget |
| read requests p95 | under 500 ms | API budget |
| write requests p95 | under 1.5 s | API budget |
| `http_req_failed` rate | under 1% | |

`song_refused_by_cap` counts songs turned away by the daily allowance or velocity limits. Those are
real limits, not failures, but if the count is high the test is measuring the limits instead of
the system.

**Run it locally** (stub GPU, so the latency numbers say nothing about the real model):

```bash
docker compose up -d
k6 run scripts/load/k6-beta.js
```

**Run it on staging**, once staging is live:

1. Dev sign-in codes are off on staging, so make the test accounts first: sign in once per account
   (200 accounts covers `PEAK=50`) and save their `bm_session` cookie values to `sessions.json` as a
   JSON array. Keep the file out of git.
2. Every request comes from one machine, so raise the per-network limits for the test window only:
   `BEATMIND_IP_REQUESTS_PER_HOUR=100000`, and set the free daily credits high for the test accounts
   at `/admin`. Put both back afterwards.
3. Start the GPU workers you plan to run in beta, at the planned count.
4. `BASE=https://staging.beatmind.ai SESSIONS=sessions.json k6 run --out json=k6-result.json scripts/load/k6-beta.js`
5. Record the summary and the Grafana queue-depth panel in `docs/09-delivery` with the sprint report,
   and set TC-REQ-NFR-01-01 in the RTM to Passed or Failed.

## Security baseline (OWASP ZAP)

The **Security baseline** workflow (`.github/workflows/security-baseline.yml`) runs the ZAP
baseline scan, a passive scan that only reads pages, against a URL you give it. Run it from the
Actions tab with *Run workflow*. It fails on any rule marked `FAIL` in `.zap/rules.tsv` and uploads
the HTML report.

- Scan staging only. Do not point it at production or at anything we do not own.
- Triage every warning: fix it, or add it to `.zap/rules.tsv` as `IGNORE` with the reason in the
  comment column, and log it in `docs/registers/review-findings.csv`.
- The active scan (`zap-full-scan`) changes data and belongs with the external penetration test
  before gate G3.
