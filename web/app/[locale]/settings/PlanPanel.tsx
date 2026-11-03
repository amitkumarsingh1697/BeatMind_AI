"use client";

import { useCallback, useEffect, useState } from "react";
import { liveSubscription, type BillingSummary } from "@/lib/billing";
import { shortDate, problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import { formatPrice } from "@/lib/pricing";

/**
 * The plan panel in Your data, with cancellation in three steps (US-037): Your data, Cancel plan
 * (why, then what happens), Confirm. The plan runs to the end of the paid period, and songs the
 * person owns stay theirs and downloadable afterwards (REQ-F-58).
 */
export default function PlanPanel({ locale }: { locale: Locale }) {
  const t = appText(locale);
  const B = t.billing;
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [step, setStep] = useState<0 | 1 | 2>(0);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState("");

  const load = useCallback(async () => {
    const r = await fetch("/v1/billing", { cache: "no-store" });
    setSummary(r.ok ? await r.json() : null);
  }, []);
  useEffect(() => {
    load();
  }, [load]);

  async function cancel() {
    setBusy(true);
    setError("");
    const r = await fetch("/v1/billing/cancel", { method: "POST" });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    const s: BillingSummary = await r.json();
    setSummary(s);
    setStep(0);
    const end = s.subscription?.currentEnd;
    setDone(end ? B.cancelled(shortDate(end, t.intlLocale)) : B.noPlan);
  }

  const live = liveSubscription(summary);
  const end = live?.currentEnd ? shortDate(live.currentEnd, t.intlLocale) : null;

  return (
    <section className="card flat stack" aria-labelledby="plan-heading">
      <h2 id="plan-heading" style={{ margin: 0 }}>
        {B.settingsHeading}
      </h2>
      {!summary ? (
        <p className="muted" style={{ margin: 0 }}>
          {t.common.loading}
        </p>
      ) : !live ? (
        <>
          <p style={{ margin: 0 }}>{summary.plan === "free" ? B.noPlan : t.account.plan(t.plans[summary.plan])}</p>
          {summary.plan === "free" && (
            <div>
              <a className="btn btn-secondary" href={`/${locale}/pricing`}>
                {B.seePlans}
              </a>
            </div>
          )}
        </>
      ) : (
        <>
          <p style={{ margin: 0, fontWeight: 600 }}>
            {t.plans[live.plan]} · {formatPrice(live.monthly, live.currency, locale)} {B.perMonth}
          </p>
          {end && (
            <p className="small muted" style={{ margin: 0 }}>
              {live.cancelAtPeriodEnd ? B.endsOn(end) : B.renews(end)}
            </p>
          )}
          {!live.cancelAtPeriodEnd && step === 0 && (
            <div>
              <button type="button" className="btn btn-secondary" onClick={() => setStep(1)}>
                {B.cancel}
              </button>
            </div>
          )}
        </>
      )}

      {step === 1 && (
        <fieldset className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className="field-label">{B.cancelStep1}</legend>
          {Object.entries(B.cancelReasons).map(([key, label]) => (
            <label key={key} className="cluster" style={{ minBlockSize: "var(--tap)" }}>
              <input type="radio" name="cancel-reason" value={key} checked={reason === key} onChange={() => setReason(key)} />
              {label}
            </label>
          ))}
          <div className="cluster">
            <button type="button" className="btn btn-ghost" onClick={() => setStep(0)}>
              {B.keep}
            </button>
            <button type="button" className="btn btn-secondary" onClick={() => setStep(2)}>
              {B.next}
            </button>
          </div>
        </fieldset>
      )}

      {step === 2 && (
        <div className="stack" role="group" aria-labelledby="cancel-what-heading">
          <h3 id="cancel-what-heading" style={{ margin: 0 }}>
            {B.cancelStep2Heading}
          </h3>
          <ul style={{ margin: 0, paddingInlineStart: "1.2em" }}>
            {B.cancelPoints.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
          <div className="cluster">
            <button type="button" className="btn btn-primary" onClick={() => setStep(0)} autoFocus>
              {B.keep}
            </button>
            <button type="button" className="btn btn-danger" onClick={cancel} disabled={busy}>
              {busy ? B.cancelling : B.confirmCancel}
            </button>
          </div>
        </div>
      )}

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {done && (
        <p className="notice" role="status">
          {done}
        </p>
      )}

      {summary && summary.periods.length > 0 && (
        <details>
          <summary style={{ cursor: "pointer", minBlockSize: "var(--tap)", display: "flex", alignItems: "center" }}>{B.payments}</summary>
          <ul className="small" style={{ margin: 0, paddingInlineStart: "1.2em" }}>
            {summary.periods.map((p) => (
              <li key={p.id}>
                {B.paymentRow(formatPrice(p.amount, p.currency, locale), shortDate(p.startsAt, t.intlLocale))} · {B.periodStatus[p.status]}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
