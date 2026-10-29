# SRE handbook

| Item | Value |
| --- | --- |
| Version | 0.1 (Batch 3), 2026-10-28 |
| Perspective | Site reliability engineering lead |
| Scope | Closed beta on free-tier and credit-funded hosting (ADR-006), with the path to a paid cloud |
| Status | Draft. No systems run yet (DEC-001). |

## 1. Service level objectives

| ID | SLO | SLI (how measured) | Target (beta) | Error budget (30 days) | Post-funding target |
| --- | --- | --- | --- | --- | --- |
| SLO-01 | Web and API availability | Share of synthetic checks and real requests to `/healthz` and core pages returning a non-5xx response | 99.0% | About 7 hours 12 minutes | 99.9% |
| SLO-02 | Download availability | Share of successful signed-URL downloads | 99.5% | About 3 hours 36 minutes | 99.95% |
| SLO-03 | Generation latency | Share of generation jobs with first take playable within 60 s | 95% | 5% of jobs | 95% within 20 s |
| SLO-04 | Generation success | Share of admitted jobs that produce at least one take, excluding moderation blocks | 98% | 2% of jobs | 99.5% |
| SLO-05 | Takedown timeliness | Share of government or court orders actioned within 3 hours (COMP-IN-02) | 100% | None | 100% |

Error budgets [C]: 30 days is 720 hours; 1% of 720 is 7.2 hours; 0.5% is 3.6 hours.

**Error budget policy:** if a budget is spent, the next sprint puts reliability work ahead of new features until the SLO recovers. SLO-05 has no budget: any miss is an S1 incident.

## 2. Monitoring and alerting

| Signal | Alert when | Severity | Route |
| --- | --- | --- | --- |
| SLO-01 burn rate | 2% of monthly budget used in 1 hour, or 5% in 6 hours | Page | On-call |
| Queue depth and oldest job age | Oldest job older than 5 minutes | Page | On-call |
| GPU worker count | Zero healthy workers while jobs are waiting | Page | On-call |
| Cost per song | Daily average above the financial-model ceiling (REQ-NFR-07) | Ticket | Founder and on-call |
| Lyria fallback spend | 80% of daily cap | Ticket; 100% stops fallback automatically | On-call |
| Database | Storage above 80% of plan; replication or backup failure | Page | On-call |
| Free-tier quotas | Any provider quota above 80% | Ticket | On-call |
| Takedown timer | Order open for 2 hours | Page | On-call and founder |
| Security | Spike in failed logins, WAF blocks, or secret-scan alert | Page | On-call |
| Payment webhooks | Signature failures or 5 consecutive delivery failures | Page | On-call |

## 3. On-call

- Weekly rotation among the three developers, handover on Monday stand-up.
- Beta hours: best effort outside 09:00 to 21:00 IST, except pages for SLO-05 and security, which are always answered.
- Response targets: page acknowledged in 15 minutes during cover hours.
- Escalation: on-call, then the area owner (scrum plan section 1), then the founder.
- Tools: Grafana alerting to a free paging channel (for example a chat app with phone notifications); status page on a free hosted status service.

## 4. Incident management

| Severity | Examples | Communication |
| --- | --- | --- |
| S1 | Data leak, wrong ownership or billing for many users, full outage, missed 3-hour order | Status page within 30 minutes; founder informed immediately; breach assessment within 24 hours (REQ-SEC-15) |
| S2 | Generation down, downloads failing for some users | Status page within 1 hour |
| S3 | Degraded latency, single feature broken | Internal |

Roles during an incident: incident lead (on-call by default), communications (founder or second developer), scribe. Write a blameless postmortem for every S1 and S2 within 5 working days using the template in section 9.

## 5. Runbooks

### RB-01 GPU workers down or saturated

1. Check the worker dashboard: healthy count, errors, provider status page.
2. If the provider is down, start workers on the secondary provider from the same container image (`infra/workers`).
3. If saturated, raise the worker cap within the budget; confirm paid-plan jobs are prioritised.
4. If still saturated, turn on the free-plan pause flag; paid plans use the Lyria fallback within its cap.
5. Update the status page if wait times exceed 5 minutes.

### RB-02 Queue backlog or stuck jobs

1. Query jobs with status `claimed` older than the job timeout; release them for retry.
2. Check the dead-letter list for a common error (bad model version, storage permission).
3. If a bad release caused it, roll back the worker image (section 7).

### RB-03 Database restore

1. Declare S1 if data loss is suspected.
2. Restore point-in-time to a new instance; never overwrite the damaged one.
3. Verify ledger chains with the verification job before switching traffic.
4. Switch the API connection string; run smoke tests; record RPO achieved.

### RB-04 Payment webhooks failing

1. Check signature errors (secret rotation) versus delivery errors (API down).
2. Fix and replay events from the provider dashboard; idempotency keys prevent double credits.
3. Run the daily reconciliation job manually.

### RB-05 Government or court takedown order (3-hour rule)

1. Record the order in admin with time received; the timer starts.
2. Disable the content (song page, embeds, downloads) and preserve evidence.
3. Notify the user unless the order forbids it.
4. Confirm action to the issuer through the stated channel; record time completed.
5. If the order is unclear, disable first and seek counsel in parallel.

### RB-06 Cost spike

1. Identify the driver: generation volume, fallback API, storage egress.
2. Look for abuse patterns (new accounts, same device or IP ranges).
3. Lower free caps or pause free generation; block abusive accounts; raise Turnstile difficulty.

### RB-07 Security incident or personal data breach

1. Contain: revoke keys and sessions, block the path.
2. Preserve logs and evidence.
3. Assess within 24 hours: what data, how many people, which countries.
4. Notify as the compliance register requires (COMP-IN-04 for India); counsel decides wording.
5. Rotate affected secrets and signing keys (REQ-SEC-07); publish key revocation if signing keys were exposed.

### RB-08 Free-tier limit reached

1. Identify the provider and limit (compute hours, storage, requests, emails).
2. Apply the documented overflow: move to the next free provider, or switch on the paid tier within the founder-approved monthly limit (Q-F02).
3. Record the event; it feeds the migration decision in section 8.

### RB-09 Model regression after an update

1. Compare the nightly model evaluation with the last good run.
2. Roll back to the previous pinned weights hash.
3. Re-run the evaluation suite and the G1 smoke set before trying again.

## 6. Backup and disaster recovery

| Data | Backup | RPO | RTO |
| --- | --- | --- | --- |
| PostgreSQL | Provider point-in-time recovery if available; otherwise nightly encrypted dump to a second provider plus WAL archiving | 24 h on dumps; 5 min with WAL | 4 h |
| Object storage | Versioning on; weekly copy of certificates and masters to a second provider | 7 days for media; 24 h for certificates | 8 h |
| Signing keys | Managed KMS with documented recovery; never exported | Not applicable | 4 h to rotate |
| Configuration | Infrastructure as code in Git | 0 | 2 h to rebuild an environment |

Restore tests run monthly in staging; results recorded.

## 7. Release and rollback

- Every merge to main deploys to staging automatically; production deploys are manual promotions of the same image.
- Database migrations are backward compatible for one release (expand, then contract).
- Feature flags guard new features; kill switches exist for free generation, fallback API, public pages and the verify endpoint.
- Rollback: redeploy the previous image tag; for workers, also pin the previous weights hash.

## 8. Capacity and migration plan

| Trigger | Action |
| --- | --- |
| Any free tier above 80% for two weeks, or funding received | Consolidate on one paid cloud with an Indian region; managed PostgreSQL with replicas; Kubernetes for API and workers |
| More than 50 queue jobs per second sustained | Move the queue to RabbitMQ or Kafka (ADR-005) |
| Daily active users above the beta plan by 3x | Add read replicas and caching for public pages |
| EU launch (Wave 3) | Second region; data-residency review |

## 9. Postmortem template

```
Title, date, severity, authors
Summary: what happened, impact (users, duration, money)
Timeline (IST)
Root causes and contributing factors
What went well, what went badly
Action items: ID, owner, due date, linked BUG or US
```
