"use client";

// Operations panels from Sprints 1 and 2 (US-013, US-019, US-030), shown in the admin console.

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { ShieldIcon } from "@/components/Icons";
import { dateTime, problem } from "@/lib/format";

type Job = { id: string; kind: string; attempts: number; error: string | null; createdAt: string; updatedAt: string };
type Caps = { freeDailyCredits: number; defaultFreeDailyCredits: number; userRequestsPerTenMinutes: number; ipRequestsPerHour: number };
type Ledger = { songs: number; events: number; valid: boolean; broken: { songId: string; brokenAtSeq: number; reason: string }[] };

export function CapsPanel() {
  const [caps, setCaps] = useState<Caps | null>(null);
  const [value, setValue] = useState("");
  const [message, setMessage] = useState("");

  useEffect(() => {
    fetch("/v1/admin/caps", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((c: Caps | null) => {
        setCaps(c);
        if (c) setValue(String(c.freeDailyCredits));
      });
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    const r = await fetch("/v1/admin/caps", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ freeDailyCredits: Number(value) }),
    });
    if (!r.ok) return setMessage((await problem(r)).detail);
    const c: Caps = await r.json();
    setCaps(c);
    setMessage(`Saved. Free accounts now get ${c.freeDailyCredits} credits a day.`);
  }

  return (
    <section className="card flat" aria-labelledby="caps-heading">
      <h2 id="caps-heading">Free daily cap</h2>
      <form onSubmit={save}>
        <div className="field">
          <label htmlFor="cap">Credits per free account per day</label>
          <input id="cap" className="input" type="number" min={0} max={1000} required value={value} onChange={(e) => setValue(e.target.value)} aria-describedby="cap-hint" />
          <p id="cap-hint" className="hint">
            One credit is one take; a song uses 2.{caps && ` Default ${caps.defaultFreeDailyCredits}. Velocity: ${caps.userRequestsPerTenMinutes} songs per account per 10 minutes, ${caps.ipRequestsPerHour} per network per hour.`}
          </p>
        </div>
        <button className="btn btn-primary" type="submit">
          Save cap
        </button>
      </form>
      {message && (
        <p className="small" role="status" style={{ marginBlockStart: "var(--space-3)" }}>
          {message}
        </p>
      )}
    </section>
  );
}

export function PlanPanel() {
  const [email, setEmail] = useState("");
  const [plan, setPlan] = useState("creator");
  const [cap, setCap] = useState("");
  const [message, setMessage] = useState("");

  async function save(e: FormEvent) {
    e.preventDefault();
    const r = await fetch("/v1/admin/users/plan", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email, plan, dailyCapOverride: cap === "" ? undefined : Number(cap) }),
    });
    setMessage(r.ok ? `${email} is now on ${plan}.` : (await problem(r)).detail);
  }

  return (
    <section className="card flat" aria-labelledby="plan-heading">
      <h2 id="plan-heading">Plan for one account</h2>
      <p className="small muted">Until payments arrive, this is how the team tries Creator and Pro.</p>
      <form onSubmit={save}>
        <div className="field">
          <label htmlFor="plan-email">Email</label>
          <input id="plan-email" className="input" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div className="grid-2">
          <div className="field">
            <label htmlFor="plan">Plan</label>
            <select id="plan" className="select" value={plan} onChange={(e) => setPlan(e.target.value)}>
              <option value="free">Free</option>
              <option value="creator">Creator</option>
              <option value="pro">Pro</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="plan-cap">Own daily cap (optional)</label>
            <input id="plan-cap" className="input" type="number" min={0} value={cap} onChange={(e) => setCap(e.target.value)} />
          </div>
        </div>
        <button className="btn btn-secondary" type="submit">
          Change plan
        </button>
      </form>
      {message && (
        <p className="small" role="status" style={{ marginBlockStart: "var(--space-3)" }}>
          {message}
        </p>
      )}
    </section>
  );
}

export function LedgerPanel() {
  const [ledger, setLedger] = useState<Ledger | null>(null);
  const [busy, setBusy] = useState(false);

  async function check() {
    setBusy(true);
    const r = await fetch("/v1/admin/ledger/verify", { cache: "no-store" });
    setBusy(false);
    setLedger(r.ok ? await r.json() : null);
  }

  return (
    <section className="card flat" aria-labelledby="ledger-heading">
      <div className="spread">
        <h2 id="ledger-heading" style={{ margin: 0 }}>
          Provenance ledger
        </h2>
        <button type="button" className="btn btn-secondary" onClick={check} disabled={busy}>
          <ShieldIcon size={18} /> {busy ? "Checking…" : "Verify every chain"}
        </button>
      </div>
      <div role="status" aria-live="polite">
        {ledger &&
          (ledger.valid ? (
            <p className="notice notice-success" style={{ marginBlockStart: "var(--space-3)" }}>
              All {ledger.songs} song chains verify ({ledger.events} events).
            </p>
          ) : (
            <div className="notice notice-danger" style={{ marginBlockStart: "var(--space-3)" }}>
              <strong>{ledger.broken.length} chain(s) do not verify.</strong>
              <ul>
                {ledger.broken.map((b) => (
                  <li key={b.songId}>
                    <code>{b.songId}</code> at event {b.brokenAtSeq}: {b.reason}
                  </li>
                ))}
              </ul>
            </div>
          ))}
      </div>
    </section>
  );
}

export function DeadJobs() {
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    const r = await fetch("/v1/admin/jobs?status=dead", { cache: "no-store" });
    setJobs(r.ok ? await r.json() : []);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function retry(id: string) {
    const r = await fetch(`/v1/admin/jobs/${id}/retry`, { method: "POST" });
    setMessage(r.ok ? "Job queued again." : `Retry failed (${r.status}).`);
    load();
  }

  return (
    <section className="card flat" aria-labelledby="jobs-heading">
      <h2 id="jobs-heading">Failed jobs</h2>
      <p className="small muted">Jobs that failed every retry. Their credits were refunded. Retrying puts a job back in the queue.</p>
      {message && (
        <p className="small" role="status">
          {message}
        </p>
      )}
      {jobs === null ? (
        <p aria-busy="true">Loading…</p>
      ) : jobs.length === 0 ? (
        <p className="badge badge-success">No failed jobs</p>
      ) : (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th scope="col">Job</th>
                <th scope="col">Attempts</th>
                <th scope="col">Last tried</th>
                <th scope="col">Error</th>
                <th scope="col">
                  <span className="visually-hidden">Action</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => (
                <tr key={j.id}>
                  <td>
                    {j.kind} <code>{j.id.slice(0, 8)}</code>
                  </td>
                  <td>{j.attempts}</td>
                  <td>{dateTime(j.updatedAt)}</td>
                  <td>{j.error ?? "No error recorded"}</td>
                  <td>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => retry(j.id)} aria-label={`Retry job ${j.id.slice(0, 8)}`}>
                      Retry
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
