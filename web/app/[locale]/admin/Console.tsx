"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import RequireAccount from "@/components/Account";
import { ShieldIcon } from "@/components/Icons";
import Player from "@/components/Player";
import { dateTime, problem } from "@/lib/format";
import type { Locale } from "@/lib/messages";
import type { Me } from "@/lib/session";
import { CapsPanel, DeadJobs, LedgerPanel, PlanPanel } from "./jobs/DeadJobs";

type Mfa = { enrolled: boolean; required: boolean; verified: boolean; recoveryCodesLeft: number };
type UserRow = {
  id: string;
  email: string;
  plan: string;
  dailyCapOverride: number | null;
  suspended: boolean;
  locale: string;
  createdAt: string;
  lastSignInAt: string | null;
  songs: number;
  openCases: number;
};
type Case = {
  id: string;
  kind: "text_block" | "lyrics_block" | "similarity";
  userId: string | null;
  songId: string | null;
  takeIdx: number | null;
  category: string | null;
  rule: string | null;
  field: string | null;
  detail: string | null;
  status: "open" | "appealed" | "upheld" | "overturned";
  appealNote: string | null;
  appealedAt: string | null;
  decisionNote: string | null;
  decidedAt: string | null;
  createdAt: string;
};
type AuditRow = { at: string; actor: string | null; action: string; target: string | null; detail: string | null };

const SECTIONS = [
  { id: "moderation", label: "Moderation" },
  { id: "users", label: "Users" },
  { id: "operations", label: "Caps and plans" },
  { id: "jobs", label: "Failed jobs" },
  { id: "audit", label: "Audit log" },
] as const;

/**
 * Admin console (US-031). Internal and English only. An admin account must pass its second
 * factor in this session before the API answers any admin call (REQ-SEC-06, TM-10); every change
 * is written to the admin audit log.
 */
export default function AdminConsole({ locale }: { locale: Locale }) {
  return (
    <RequireAccount locale={locale}>
      {(me) =>
        !me.admin ? (
          <div className="notice" role="alert">
            Only admins can see this page. Sign in with an admin account.
          </div>
        ) : me.adminVerified ? (
          <Console />
        ) : (
          <SecondFactor me={me} />
        )
      }
    </RequireAccount>
  );
}

function Console() {
  return (
    <div className="stack-lg">
      <header className="page-head">
        <p className="eyebrow">Admin</p>
        <h1>Console</h1>
        <p>Every change here is written to the admin audit log.</p>
        <nav aria-label="Console sections" className="cluster">
          {SECTIONS.map((s) => (
            <a key={s.id} href={`#${s.id}`} className="btn btn-ghost btn-sm">
              {s.label}
            </a>
          ))}
        </nav>
      </header>
      <ModerationQueue />
      <UsersPanel />
      <div id="operations" className="grid-2">
        <CapsPanel />
        <PlanPanel />
      </div>
      <LedgerPanel />
      <div id="jobs">
        <DeadJobs />
      </div>
      <AuditPanel />
    </div>
  );
}

/** Enrol (authenticator app plus recovery codes) or verify the second factor for this session. */
function SecondFactor({ me }: { me: Me }) {
  const [mfa, setMfa] = useState<Mfa | null>(null);
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetch("/v1/me/mfa", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then(setMfa);
  }, []);

  async function start() {
    setError("");
    const r = await fetch("/v1/me/mfa/setup", { method: "POST" });
    if (!r.ok) return setError((await problem(r)).detail);
    setSetup(await r.json());
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const enrolling = !mfa?.enrolled;
    const r = await fetch(enrolling ? "/v1/me/mfa/confirm" : "/v1/me/mfa/verify", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code }),
    });
    setBusy(false);
    if (!r.ok) return setError((await problem(r)).detail);
    if (enrolling) setCodes((await r.json()).recoveryCodes);
    else window.location.reload();
  }

  const box = { maxInlineSize: 520, marginInline: "auto" } as const;

  if (mfa === null) {
    return (
      <p className="cluster muted" aria-busy="true">
        <span className="spinner" aria-hidden="true" /> Loading…
      </p>
    );
  }

  if (codes) {
    return (
      <section className="card stack" style={box} aria-labelledby="codes-heading">
        <h1 id="codes-heading">Save your recovery codes</h1>
        <p>
          Each code works once, if you lose your phone. Store them in your password manager. They are not shown again.
        </p>
        <ul className="codes" aria-label="Recovery codes">
          {codes.map((c) => (
            <li key={c}>
              <code>{c}</code>
            </li>
          ))}
        </ul>
        <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
          I have saved them, open the console
        </button>
      </section>
    );
  }

  return (
    <section className="card stack" style={box} aria-labelledby="mfa-heading">
      <p className="eyebrow" style={{ margin: 0 }}>
        <ShieldIcon size={16} /> Admin
      </p>
      <h1 id="mfa-heading" style={{ margin: 0 }}>
        {mfa.enrolled ? "Enter your second-factor code" : "Set up a second factor"}
      </h1>
      <p className="muted" style={{ margin: 0 }}>
        {me.email} is an admin account. The console opens only after a code from your authenticator app, once per session.
      </p>

      {!mfa.enrolled && !setup && (
        <div>
          <button type="button" className="btn btn-primary" onClick={start}>
            Start setup
          </button>
        </div>
      )}

      {!mfa.enrolled && setup && (
        <ol className="stack">
          <li>
            Open your authenticator app (Google Authenticator, Microsoft Authenticator, 1Password or similar) and add an
            account. On a phone, <a href={setup.otpauthUri}>tap here to add it</a>; otherwise type this key:
            <p className="secret">
              <code aria-label="Setup key">{setup.secret.match(/.{1,4}/g)?.join(" ")}</code>
            </p>
          </li>
          <li>Enter the 6-digit code the app shows.</li>
        </ol>
      )}

      {(mfa.enrolled || setup) && (
        <form onSubmit={submit} className="stack">
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor="mfa-code">{mfa.enrolled ? "Code from your app, or a recovery code" : "6-digit code"}</label>
            <input
              id="mfa-code"
              className="input"
              autoComplete="one-time-code"
              inputMode={mfa.enrolled ? "text" : "numeric"}
              required
              value={code}
              onChange={(e) => setCode(e.target.value.trim())}
              autoFocus
            />
          </div>
          <div>
            <button className="btn btn-primary" type="submit" disabled={busy}>
              {busy ? "Checking…" : mfa.enrolled ? "Verify" : "Confirm and get recovery codes"}
            </button>
          </div>
          {mfa.enrolled && (
            <p className="small muted" style={{ margin: 0 }}>
              {mfa.recoveryCodesLeft} recovery codes left.
            </p>
          )}
        </form>
      )}
      {error && (
        <p className="notice notice-danger" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}

function parseDetail(detail: string | null): Record<string, unknown> {
  if (!detail) return {};
  try {
    return JSON.parse(detail);
  } catch {
    return {};
  }
}

const KIND: Record<Case["kind"], string> = {
  text_block: "Blocked request",
  lyrics_block: "AI lyrics held back",
  similarity: "Take held back (similarity)",
};

/** Appeals first, then open cases (REQ-F-59: answer appeals within 72 hours). */
function ModerationQueue() {
  const [status, setStatus] = useState<"" | Case["status"]>("");
  const [cases, setCases] = useState<Case[] | null>(null);
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    const r = await fetch(`/v1/admin/moderation${status ? `?status=${status}` : ""}`, { cache: "no-store" });
    setCases(r.ok ? await r.json() : []);
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  async function decide(c: Case, decision: "uphold" | "overturn", note: string) {
    const r = await fetch(`/v1/admin/moderation/${c.id}/decision`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ decision, note: note || undefined }),
    });
    setMessage(r.ok ? `Case ${c.id.slice(0, 8)} ${decision === "overturn" ? "overturned" : "upheld"}.` : (await problem(r)).detail);
    load();
  }

  return (
    <section id="moderation" className="card flat stack" aria-labelledby="moderation-heading">
      <div className="spread">
        <h2 id="moderation-heading" style={{ margin: 0 }}>
          Moderation queue
        </h2>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="case-status" className="visually-hidden">
            Show
          </label>
          <select id="case-status" className="select" value={status} onChange={(e) => setStatus(e.target.value as typeof status)}>
            <option value="">Needs a decision</option>
            <option value="appealed">Appealed</option>
            <option value="open">Open</option>
            <option value="upheld">Upheld</option>
            <option value="overturned">Overturned</option>
          </select>
        </div>
      </div>
      <p className="small muted" style={{ margin: 0 }}>
        Blocked text is not stored, only its fingerprint, so a blocked request shows the rule that matched. Overturning a held-back
        take releases it to its owner; overturning a blocked request lets that exact text through for that account.
      </p>
      <p className="small" role="status">
        {message}
      </p>
      {cases === null ? (
        <p aria-busy="true">Loading…</p>
      ) : cases.length === 0 ? (
        <p className="badge badge-success">Nothing waiting</p>
      ) : (
        <ul className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {cases.map((c) => (
            <CaseCard key={c.id} c={c} onDecide={decide} />
          ))}
        </ul>
      )}
    </section>
  );
}

function CaseCard({ c, onDecide }: { c: Case; onDecide: (c: Case, d: "uphold" | "overturn", note: string) => void }) {
  const [note, setNote] = useState("");
  const detail = parseDetail(c.detail);
  const open = c.status === "open" || c.status === "appealed";
  const heading = `${KIND[c.kind]} · ${c.category ?? "similarity"}`;
  return (
    <li className="card tight stack">
      <div className="spread">
        <strong>{heading}</strong>
        <span className={c.status === "appealed" ? "badge badge-accent" : c.status === "overturned" ? "badge badge-success" : "badge"}>
          {c.status}
        </span>
      </div>
      <dl className="facts small">
        <dt>Case</dt>
        <dd>
          <code>{c.id}</code>
        </dd>
        <dt>Created</dt>
        <dd>{dateTime(c.createdAt)}</dd>
        {c.rule && (
          <>
            <dt>Rule</dt>
            <dd>
              <code>{c.rule}</code> in {c.field}
            </dd>
          </>
        )}
        {c.kind === "similarity" && (
          <>
            <dt>Closest reference</dt>
            <dd>
              {String(detail.referenceTitle ?? "unknown")} · score {String(detail.score ?? "?")} · {String(detail.matched ?? "?")}{" "}
              matching landmarks
            </dd>
          </>
        )}
        {c.appealNote && (
          <>
            <dt>Appeal</dt>
            <dd>{c.appealNote}</dd>
          </>
        )}
        {c.decisionNote && (
          <>
            <dt>Decision note</dt>
            <dd>{c.decisionNote}</dd>
          </>
        )}
      </dl>
      {c.kind === "similarity" && c.songId && c.takeIdx && (
        <Player src={`/v1/admin/songs/${c.songId}/takes/${c.takeIdx}/audio`} title={`Held-back take ${c.takeIdx}`} compact />
      )}
      {open && (
        <div className="stack">
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor={`note-${c.id}`}>Note to the creator (optional)</label>
            <input id={`note-${c.id}`} className="input" maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="cluster">
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => onDecide(c, "uphold", note)}>
              Uphold
            </button>
            <button type="button" className="btn btn-primary btn-sm" onClick={() => onDecide(c, "overturn", note)}>
              {c.kind === "similarity" ? "Overturn and release take" : "Overturn"}
            </button>
          </div>
        </div>
      )}
    </li>
  );
}

function UsersPanel() {
  const [q, setQ] = useState("");
  const [users, setUsers] = useState<UserRow[] | null>(null);
  const [message, setMessage] = useState("");

  const load = useCallback(async (query: string) => {
    const r = await fetch(`/v1/admin/users?q=${encodeURIComponent(query)}`, { cache: "no-store" });
    setUsers(r.ok ? await r.json() : []);
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => load(q), 250);
    return () => clearTimeout(timer);
  }, [q, load]);

  async function toggle(u: UserRow) {
    const suspended = !u.suspended;
    const note = window.prompt(suspended ? `Why pause ${u.email}?` : `Why resume ${u.email}?`) ?? undefined;
    if (note === undefined) return;
    const r = await fetch("/v1/admin/users/suspend", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: u.email, suspended, note }),
    });
    setMessage(r.ok ? `${u.email} ${suspended ? "paused" : "resumed"}.` : (await problem(r)).detail);
    load(q);
  }

  return (
    <section id="users" className="card flat stack" aria-labelledby="users-heading">
      <h2 id="users-heading" style={{ margin: 0 }}>
        Users
      </h2>
      <div className="field" style={{ margin: 0 }}>
        <label htmlFor="user-search">Search by email</label>
        <input id="user-search" className="input" type="search" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      <p className="small" role="status">
        {message}
      </p>
      {users === null ? (
        <p aria-busy="true">Loading…</p>
      ) : (
        <div className="table-wrap">
          <table className="data">
            <caption className="visually-hidden">Accounts, most recent sign-in first</caption>
            <thead>
              <tr>
                <th scope="col">Email</th>
                <th scope="col">Plan</th>
                <th scope="col">Own cap</th>
                <th scope="col">Songs</th>
                <th scope="col">Open cases</th>
                <th scope="col">Last sign-in</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.email}</td>
                  <td>{u.plan}</td>
                  <td>{u.dailyCapOverride ?? "default"}</td>
                  <td>{u.songs}</td>
                  <td>{u.openCases}</td>
                  <td>{u.lastSignInAt ? dateTime(u.lastSignInAt) : "never"}</td>
                  <td>
                    <button
                      type="button"
                      className={u.suspended ? "btn btn-secondary btn-sm" : "btn btn-ghost btn-sm"}
                      onClick={() => toggle(u)}
                      aria-label={`${u.suspended ? "Resume" : "Pause"} ${u.email}`}
                    >
                      {u.suspended ? "Paused · resume" : "Active · pause"}
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

function AuditPanel() {
  const [rows, setRows] = useState<AuditRow[] | null>(null);

  useEffect(() => {
    fetch("/v1/admin/audit", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : []))
      .then(setRows);
  }, []);

  return (
    <section id="audit" className="card flat stack" aria-labelledby="audit-heading">
      <h2 id="audit-heading" style={{ margin: 0 }}>
        Audit log
      </h2>
      {rows === null ? (
        <p aria-busy="true">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="muted">No admin actions yet.</p>
      ) : (
        <div className="table-wrap">
          <table className="data">
            <caption className="visually-hidden">Latest 50 admin actions</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">Action</th>
                <th scope="col">Target</th>
                <th scope="col">Note</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((a, i) => (
                <tr key={`${a.at}-${i}`}>
                  <td>{dateTime(a.at)}</td>
                  <td>{a.actor ?? "deleted account"}</td>
                  <td>
                    <code>{a.action}</code>
                  </td>
                  <td>
                    <code className="small">{a.target}</code>
                  </td>
                  <td>{a.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
