"use client";

import { useState, type FormEvent } from "react";
import { dateTime, problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import { useTurnstile } from "@/lib/useTurnstile";

const KINDS = ["rights_holder", "user", "government_order", "court_order"];
const RIGHTS = ["copyright", "voice", "name_or_likeness", "privacy", "unlawful", "other"];

type Sent = { reference: string; songFound: boolean; acknowledgeBy: string };

/** The public report form (US-032). Orders get the 3-hour clock; everything else 24 hours to acknowledge. */
export default function ReportForm({ locale, target: initial }: { locale: Locale; target: string }) {
  const t = appText(locale);
  const R = t.report;
  const [target, setTarget] = useState(initial);
  const [kind, setKind] = useState("rights_holder");
  const [right, setRight] = useState("copyright");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [description, setDescription] = useState("");
  const [evidence, setEvidence] = useState("");
  const [order, setOrder] = useState("");
  const [accurate, setAccurate] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [sent, setSent] = useState<Sent | null>(null);
  const turnstile = useTurnstile();
  const isOrder = kind === "government_order" || kind === "court_order";
  const ready = target.trim() && name.trim() && email.includes("@") && description.trim() && accurate && (!isOrder || order.trim());

  async function send(e: FormEvent) {
    e.preventDefault();
    if (!ready) return;
    setBusy(true);
    setError("");
    const r = await fetch("/v1/reports", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        target,
        reporterKind: kind,
        reporterName: name,
        reporterEmail: email,
        rightClaimed: right,
        description,
        evidenceUrl: evidence || undefined,
        orderReference: isOrder ? order : undefined,
        accurate,
        turnstileToken: turnstile.token || undefined,
      }),
    });
    setBusy(false);
    turnstile.reset();
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    setSent(await r.json());
  }

  if (sent) {
    return (
      <div className="notice notice-success stack" role="status">
        <p style={{ margin: 0, fontWeight: 700 }}>{R.sent(sent.reference, dateTime(sent.acknowledgeBy, t.intlLocale))}</p>
        {!sent.songFound && <p style={{ margin: 0 }}>{R.notFound}</p>}
      </div>
    );
  }

  return (
    <form className="card stack" onSubmit={send} noValidate>
      <div className="field">
        <label htmlFor="report-target">{R.target}</label>
        <input id="report-target" className="input" value={target} maxLength={500} onChange={(e) => setTarget(e.target.value)} aria-describedby="report-target-hint" required />
        <p id="report-target-hint" className="hint">
          {R.targetHint}
        </p>
      </div>
      <div className="grid-2">
        <fieldset className="field">
          <legend>{R.who}</legend>
          {KINDS.map((k) => (
            <label key={k} className="check">
              <input type="radio" name="kind" value={k} checked={kind === k} onChange={() => setKind(k)} />
              <span>{R.kinds[k]}</span>
            </label>
          ))}
        </fieldset>
        <fieldset className="field">
          <legend>{R.right}</legend>
          {RIGHTS.map((k) => (
            <label key={k} className="check">
              <input type="radio" name="right" value={k} checked={right === k} onChange={() => setRight(k)} />
              <span>{R.rights[k]}</span>
            </label>
          ))}
        </fieldset>
      </div>
      {isOrder && (
        <div className="field">
          <label htmlFor="report-order">{R.order}</label>
          <input id="report-order" className="input" value={order} maxLength={200} onChange={(e) => setOrder(e.target.value)} required />
        </div>
      )}
      <div className="grid-2">
        <div className="field">
          <label htmlFor="report-name">{R.name}</label>
          <input id="report-name" className="input" autoComplete="name" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="field">
          <label htmlFor="report-email">{R.email}</label>
          <input id="report-email" className="input" type="email" autoComplete="email" value={email} maxLength={320} onChange={(e) => setEmail(e.target.value)} required />
        </div>
      </div>
      <div className="field">
        <label htmlFor="report-description">{R.description}</label>
        <textarea id="report-description" className="textarea" rows={5} maxLength={5000} value={description} onChange={(e) => setDescription(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="report-evidence">{R.evidence}</label>
        <input id="report-evidence" className="input" type="url" inputMode="url" value={evidence} maxLength={1000} onChange={(e) => setEvidence(e.target.value)} placeholder="https://" />
      </div>
      <label className="check">
        <input type="checkbox" checked={accurate} onChange={(e) => setAccurate(e.target.checked)} />
        <span>{R.accurate}</span>
      </label>
      <p className="small muted" style={{ margin: 0 }}>
        {R.falseReports}
      </p>
      {turnstile.enabled && <div ref={turnstile.ref} />}
      {error && (
        <p className="notice notice-danger" role="alert">
          {error}
        </p>
      )}
      <div>
        <button className="btn btn-primary" type="submit" disabled={busy || !ready}>
          {busy ? R.sending : R.send}
        </button>
      </div>
    </form>
  );
}
