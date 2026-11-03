"use client";

import { useId, useState } from "react";
import { problem, type Problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";

/**
 * A request our safety check refused (US-028): says which rule applied and lets the creator ask a
 * person to review it (REQ-F-59, answer within 72 hours). The text itself is not kept, only a hash.
 */
export default function Blocked({ locale, p, onClose }: { locale: Locale; p: Problem; onClose?: () => void }) {
  const t = appText(locale);
  const id = useId();
  const [note, setNote] = useState("");
  const [state, setState] = useState<"idle" | "open" | "sending" | "sent">("idle");
  const [error, setError] = useState("");

  async function send() {
    if (!p.caseId) return;
    setState("sending");
    setError("");
    const r = await fetch(`/v1/moderation/cases/${p.caseId}/appeal`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ note: note.trim() || undefined }),
    });
    if (r.ok || r.status === 409) return setState("sent");
    setError(errorText(locale, await problem(r)));
    setState("open");
  }

  return (
    <section className="notice notice-danger stack" aria-labelledby={`${id}-heading`}>
      <div role="alert" className="stack">
        <strong id={`${id}-heading`}>{t.create.blockedHeading}</strong>
        <p style={{ margin: 0 }}>{errorText(locale, p)}</p>
      </div>
      {p.caseId && state === "idle" && (
        <div className="cluster">
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => setState("open")}>
            {t.create.appeal}
          </button>
          {onClose && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>
              {t.common.close}
            </button>
          )}
        </div>
      )}
      {(state === "open" || state === "sending") && (
        // Not a form: this notice can sit inside the song form, and forms must not nest.
        <div className="stack" role="group" aria-labelledby={`${id}-heading`}>
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor={`${id}-note`}>{t.create.appealNote}</label>
            <textarea id={`${id}-note`} className="textarea" rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div className="cluster">
            <button type="button" className="btn btn-primary btn-sm" disabled={state === "sending"} onClick={send}>
              {state === "sending" ? t.common.sending : t.create.appealSend}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setState("idle")}>
              {t.common.cancel}
            </button>
          </div>
          {error && <p className="error">{error}</p>}
        </div>
      )}
      {state === "sent" && (
        <p role="status" style={{ margin: 0 }}>
          {t.create.appealSent}
        </p>
      )}
    </section>
  );
}
