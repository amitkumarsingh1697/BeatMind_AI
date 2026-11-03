"use client";

import { useRef, useState } from "react";
import RequireAccount, { AccountBar } from "@/components/Account";
import { DownloadIcon, TrashIcon } from "@/components/Icons";
import { problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import type { Me } from "@/lib/session";

export default function YourData({ locale }: { locale: Locale }) {
  return <RequireAccount locale={locale}>{(me) => <Panels me={me} locale={locale} />}</RequireAccount>;
}

/**
 * Data export and account deletion (US-011). The export is a plain link: the browser downloads the
 * ZIP itself. Deletion asks for the email again, in a dialog that starts on Cancel.
 */
function Panels({ me, locale }: { me: Me; locale: Locale }) {
  const t = appText(locale);
  const S = t.settings;
  const dialog = useRef<HTMLDialogElement>(null);
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [deleted, setDeleted] = useState(false);
  const matches = confirm.trim().toLowerCase() === me.email.toLowerCase();

  async function remove() {
    if (!matches) return;
    setBusy(true);
    setError("");
    const r = await fetch("/v1/me", {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ confirmEmail: confirm.trim() }),
    });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    dialog.current?.close();
    setDeleted(true);
  }

  if (deleted) {
    return (
      <div className="empty" role="status">
        <h1>{S.deleted}</h1>
        <a className="btn btn-secondary" href={`/${locale}`}>
          BeatMind AI
        </a>
      </div>
    );
  }

  return (
    <div className="stack-lg">
      <AccountBar me={me} locale={locale} />
      <header className="page-head">
        <p className="eyebrow">{S.eyebrow}</p>
        <h1>{S.heading}</h1>
        <p>{S.intro}</p>
      </header>
      <section className="card flat stack" aria-labelledby="export-heading">
        <h2 id="export-heading" style={{ margin: 0 }}>
          {S.exportHeading}
        </h2>
        <p style={{ margin: 0 }}>{S.exportText}</p>
        <div>
          <a className="btn btn-primary" href="/v1/me/export" download>
            <DownloadIcon size={18} /> {S.exportButton}
          </a>
        </div>
      </section>
      <section className="card flat stack" aria-labelledby="delete-heading">
        <h2 id="delete-heading" style={{ margin: 0 }}>
          {S.deleteHeading}
        </h2>
        <p style={{ margin: 0 }}>{S.deleteText}</p>
        <div>
          <button type="button" className="btn btn-danger" onClick={() => dialog.current?.showModal()}>
            <TrashIcon size={18} /> {S.deleteButton}
          </button>
        </div>
      </section>
      <dialog ref={dialog} aria-labelledby="confirm-delete" onClose={() => setConfirm("")}>
        <div className="stack">
          <h2 id="confirm-delete">{S.confirmHeading}</h2>
          <p className="small">{S.deleteText}</p>
          <div className="field">
            <label htmlFor="confirm-email">{S.confirmLabel(me.email)}</label>
            <input id="confirm-email" className="input" type="email" autoComplete="off" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          </div>
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
          <div className="cluster" style={{ justifyContent: "flex-end" }}>
            <button type="button" className="btn btn-ghost" onClick={() => dialog.current?.close()} autoFocus>
              {t.common.cancel}
            </button>
            <button type="button" className="btn btn-danger" onClick={remove} disabled={busy || !matches}>
              {busy ? S.deleting : S.confirmButton}
            </button>
          </div>
        </div>
      </dialog>
    </div>
  );
}
