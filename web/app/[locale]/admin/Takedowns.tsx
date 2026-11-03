"use client";

import { useCallback, useEffect, useState } from "react";
import { dateTime, problem } from "@/lib/format";

type Takedown = {
  id: string;
  songId: string | null;
  songTitle: string | null;
  certificateId: string | null;
  reporterKind: "rights_holder" | "user" | "government_order" | "court_order";
  reporterName: string;
  reporterEmail: string;
  rightClaimed: string;
  description: string;
  evidenceUrl: string | null;
  orderReference: string | null;
  status: "received" | "acknowledged" | "actioned" | "rejected" | "appealed" | "restored";
  ackDueAt: string;
  actionDueAt: string;
  appealNote: string | null;
  appealDueAt: string | null;
  decisionNote: string | null;
  createdAt: string;
  clock: "acknowledge" | "act" | "appeal" | null;
  dueAt: string | null;
  overdue: boolean;
};

const KIND: Record<Takedown["reporterKind"], string> = {
  rights_holder: "Rights holder",
  user: "Listener",
  government_order: "Government order",
  court_order: "Court order",
};

const CLOCK: Record<string, string> = { acknowledge: "Acknowledge by", act: "Act by", appeal: "Answer appeal by" };

/** Minutes or hours left, or how long overdue, in plain words. */
function left(due: string, now: number): string {
  const minutes = Math.round((new Date(due).getTime() - now) / 60000);
  const abs = Math.abs(minutes);
  const text = abs < 90 ? `${abs} min` : abs < 48 * 60 ? `${Math.round(abs / 60)} h` : `${Math.round(abs / 1440)} days`;
  return minutes < 0 ? `${text} overdue` : `${text} left`;
}

/**
 * Takedown queue (US-032, REQ-F-54, REQ-F-59, SRE runbook RB-05): nearest deadline first. Orders
 * from an Indian court or government agency have 3 hours; other reports 24 hours to acknowledge
 * and 15 days to act; appeals 72 hours. Overdue items are flagged and raise the overdue metric.
 */
export default function TakedownQueue() {
  const [status, setStatus] = useState("");
  const [items, setItems] = useState<Takedown[] | null>(null);
  const [message, setMessage] = useState("");
  const [now, setNow] = useState(() => Date.now());

  const load = useCallback(async () => {
    const r = await fetch(`/v1/admin/takedowns${status ? `?status=${status}` : ""}`, { cache: "no-store" });
    setItems(r.ok ? await r.json() : []);
    setNow(Date.now());
  }, [status]);

  useEffect(() => {
    load();
    const timer = window.setInterval(() => setNow(Date.now()), 60000);
    return () => window.clearInterval(timer);
  }, [load]);

  async function act(t: Takedown, path: string, body: Record<string, unknown>, done: string) {
    const r = await fetch(`/v1/admin/takedowns/${t.id}/${path}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    setMessage(r.ok ? `Report ${t.id.slice(0, 8)}: ${done}.` : (await problem(r)).detail);
    load();
  }

  const overdue = items?.filter((t) => t.overdue).length ?? 0;

  return (
    <section id="takedowns" className="card flat stack" aria-labelledby="takedowns-heading">
      <div className="spread">
        <h2 id="takedowns-heading" style={{ margin: 0 }}>
          Takedowns and appeals
        </h2>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="takedown-status" className="visually-hidden">
            Show
          </label>
          <select id="takedown-status" className="select" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Needs a person</option>
            <option value="received">Received</option>
            <option value="acknowledged">Acknowledged</option>
            <option value="appealed">Appealed</option>
            <option value="actioned">Removed</option>
            <option value="rejected">Rejected</option>
            <option value="restored">Restored</option>
          </select>
        </div>
      </div>
      <p className="small muted" style={{ margin: 0 }}>
        Court and government orders: act within 3 hours. Other reports: acknowledge within 24 hours, act within 15 days. Appeals: answer
        within 72 hours. Removing a song turns off its downloads, share links and certificate; restoring puts them back.
      </p>
      {overdue > 0 && (
        <p className="notice notice-danger" role="alert">
          {overdue} past deadline
        </p>
      )}
      <p className="small" role="status">
        {message}
      </p>
      {items === null ? (
        <p aria-busy="true">Loading…</p>
      ) : items.length === 0 ? (
        <p className="badge badge-success">Nothing waiting</p>
      ) : (
        <ul className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {items.map((t) => (
            <TakedownCard key={t.id} t={t} now={now} onAct={act} />
          ))}
        </ul>
      )}
    </section>
  );
}

function TakedownCard({
  t,
  now,
  onAct,
}: {
  t: Takedown;
  now: number;
  onAct: (t: Takedown, path: string, body: Record<string, unknown>, done: string) => void;
}) {
  const [note, setNote] = useState("");
  const noteId = `takedown-note-${t.id}`;
  const order = t.reporterKind === "government_order" || t.reporterKind === "court_order";
  return (
    <li className={t.overdue ? "card tight stack overdue" : "card tight stack"}>
      <div className="spread">
        <span className="cluster">
          <span className={order ? "badge badge-danger" : "badge"}>{KIND[t.reporterKind]}</span>
          <span className="badge">{t.rightClaimed.replaceAll("_", " ")}</span>
          <span className="small muted">{t.status}</span>
        </span>
        {t.clock && t.dueAt && (
          <span className={t.overdue ? "badge badge-danger" : "badge badge-accent"}>
            {CLOCK[t.clock]} {dateTime(t.dueAt, "en-IN")} · {left(t.dueAt, now)}
          </span>
        )}
      </div>
      <dl className="facts small">
        <dt>Song</dt>
        <dd>{t.songId ? `${t.songTitle ?? "Untitled"} (${t.songId.slice(0, 8)})` : "Not matched: look it up from the description"}</dd>
        <dt>From</dt>
        <dd>
          {t.reporterName} · <a href={`mailto:${t.reporterEmail}`}>{t.reporterEmail}</a>
        </dd>
        {t.orderReference && (
          <>
            <dt>Order</dt>
            <dd>{t.orderReference}</dd>
          </>
        )}
        <dt>Report</dt>
        <dd style={{ whiteSpace: "pre-wrap" }}>{t.description}</dd>
        {t.evidenceUrl && (
          <>
            <dt>Evidence</dt>
            <dd>
              <a href={t.evidenceUrl} rel="noopener noreferrer nofollow" target="_blank">
                {t.evidenceUrl}
              </a>
            </dd>
          </>
        )}
        {t.appealNote && (
          <>
            <dt>Owner&apos;s appeal</dt>
            <dd style={{ whiteSpace: "pre-wrap" }}>{t.appealNote}</dd>
          </>
        )}
        <dt>Received</dt>
        <dd>{dateTime(t.createdAt, "en-IN")}</dd>
      </dl>
      {(t.status === "received" || t.status === "acknowledged" || t.status === "appealed") && (
        <div className="stack">
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor={noteId}>Note (the owner sees it)</label>
            <input id={noteId} className="input" value={note} maxLength={2000} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="cluster">
            {t.status === "received" && (
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => onAct(t, "acknowledge", {}, "acknowledged")}>
                Acknowledge
              </button>
            )}
            {t.status !== "appealed" ? (
              <>
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  disabled={!t.songId}
                  onClick={() => onAct(t, "decision", { decision: "disable", note: note || undefined }, "song removed")}
                >
                  Remove the song
                </button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => onAct(t, "decision", { decision: "reject", note: note || undefined }, "rejected")}>
                  Reject the report
                </button>
              </>
            ) : (
              <>
                <button type="button" className="btn btn-primary btn-sm" onClick={() => onAct(t, "appeal-decision", { decision: "restore", note: note || undefined }, "song restored")}>
                  Restore the song
                </button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => onAct(t, "appeal-decision", { decision: "uphold", note: note || undefined }, "removal upheld")}>
                  Keep it removed
                </button>
              </>
            )}
          </div>
        </div>
      )}
    </li>
  );
}
