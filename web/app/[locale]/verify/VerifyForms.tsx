"use client";

import { useEffect, useState, type FormEvent } from "react";
import { ShieldIcon } from "@/components/Icons";
import { problem, shortDate } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import { useTurnstile } from "@/lib/useTurnstile";

type Answer = {
  verdict: "beatmind" | "not_found" | "unreadable";
  match?: string;
  madeOn?: string;
  aiGenerated?: boolean;
  lyrics?: "none" | "person" | "ai";
  edited?: boolean;
  removed?: boolean;
  message: string;
  otherCredentials?: string;
  certificate?: { id: string; status: "valid" | "revoked"; scope: string; url: string };
};
type Check = { id: string; status: "checking" | "done" | "failed"; result: Answer | null };

const MAX_MB = 30;

/** Checks by certificate ID or by file, and shows the coarse public answer (REQ-F-32). */
export default function VerifyForms({ locale, certificate: initial }: { locale: Locale; certificate: string }) {
  const t = appText(locale);
  const V = t.verify;
  const [certificate, setCertificate] = useState(initial);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [check, setCheck] = useState<Check | null>(null);
  const turnstile = useTurnstile();

  // A file the worker is still reading: ask again every two seconds.
  useEffect(() => {
    if (check?.status !== "checking") return;
    const timer = setTimeout(async () => {
      const r = await fetch(`/v1/verify/${check.id}`, { cache: "no-store" });
      if (r.ok) setCheck(await r.json());
    }, 2000);
    return () => clearTimeout(timer);
  }, [check]);

  async function send(body: BodyInit, json: boolean) {
    setBusy(true);
    setError("");
    setCheck(null);
    const r = await fetch("/v1/verify", { method: "POST", headers: json ? { "content-type": "application/json" } : undefined, body });
    setBusy(false);
    turnstile.reset();
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    setCheck(await r.json());
  }

  function byCertificate(e: FormEvent) {
    e.preventDefault();
    if (!certificate.trim()) return;
    send(JSON.stringify({ certificateId: certificate.trim(), turnstileToken: turnstile.token || undefined }), true);
  }

  function byFile(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    if (file.size > MAX_MB * 1024 * 1024) return setError(t.errors.invalid_file);
    const form = new FormData();
    form.append("file", file);
    if (turnstile.token) form.append("turnstileToken", turnstile.token);
    send(form, false);
  }

  return (
    <div className="stack-lg">
      <div className="grid-2" style={{ alignItems: "start" }}>
        <form className="card flat stack" onSubmit={byCertificate} aria-labelledby="verify-cert-heading">
          <h2 id="verify-cert-heading" style={{ margin: 0 }}>
            {V.byCertificate}
          </h2>
          <div className="field">
            <label htmlFor="verify-cert">{V.certificateLabel}</label>
            <input
              id="verify-cert"
              className="input"
              autoComplete="off"
              spellCheck={false}
              maxLength={40}
              value={certificate}
              onChange={(e) => setCertificate(e.target.value)}
              aria-describedby="verify-cert-hint"
            />
            <p className="hint" id="verify-cert-hint">
              {V.certificateHint}
            </p>
          </div>
          <div>
            <button type="submit" className="btn btn-primary" disabled={busy || !certificate.trim()}>
              {V.check}
            </button>
          </div>
        </form>
        <form className="card flat stack" onSubmit={byFile} aria-labelledby="verify-file-heading">
          <h2 id="verify-file-heading" style={{ margin: 0 }}>
            {V.byFile}
          </h2>
          <div className="field">
            <label htmlFor="verify-file">{V.fileLabel}</label>
            <input
              id="verify-file"
              className="input"
              type="file"
              accept="audio/mpeg,audio/wav,audio/x-wav,audio/flac,audio/mp4,.mp3,.wav,.flac,.m4a"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              aria-describedby="verify-file-hint"
            />
            <p className="hint" id="verify-file-hint">
              {V.fileNote}
            </p>
          </div>
          <div>
            <button type="submit" className="btn btn-primary" disabled={busy || !file}>
              {V.check}
            </button>
          </div>
        </form>
      </div>
      <div ref={turnstile.ref} />

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <div aria-live="polite">
        {check?.status === "checking" && (
          <div className="notice cluster" role="status">
            <span className="spinner" aria-hidden="true" /> {V.checking}
          </div>
        )}
        {check?.result && <Result answer={check.result} locale={locale} />}
      </div>
    </div>
  );
}

function Result({ answer, locale }: { answer: Answer; locale: Locale }) {
  const t = appText(locale);
  const V = t.verify;
  const yes = answer.verdict === "beatmind";
  return (
    <section className={yes ? "notice notice-ai stack" : "notice stack"} aria-labelledby="verify-result-heading">
      <h2 id="verify-result-heading" className="cluster" style={{ margin: 0 }}>
        {yes && <ShieldIcon size={22} />}
        {yes ? V.yes : answer.verdict === "unreadable" ? V.unreadable : V.no}
      </h2>
      {yes ? (
        <ul style={{ margin: 0, paddingInlineStart: "1.2em" }}>
          {answer.match && V.match[answer.match] && <li>{V.match[answer.match]}</li>}
          {answer.madeOn && <li>{V.madeOn(shortDate(answer.madeOn, t.intlLocale))}</li>}
          <li>{V.aiGenerated}</li>
          {answer.lyrics && <li>{V.lyrics[answer.lyrics]}</li>}
          {answer.edited && <li>{V.edited}</li>}
          {answer.removed && <li>{V.removed}</li>}
        </ul>
      ) : (
        <p style={{ margin: 0 }}>{locale === "en" ? answer.message : answer.verdict === "unreadable" ? V.unreadableNote : V.notFoundNote}</p>
      )}
      {answer.otherCredentials && <p className="small" style={{ margin: 0 }}>{answer.otherCredentials}</p>}
      {answer.certificate && (
        <p style={{ margin: 0 }}>
          {V.certificate(V.certificateStatus[answer.certificate.status])} ·{" "}
          <a href={`/${locale}${answer.certificate.url}`}>{V.openCertificate}</a>
        </p>
      )}
    </section>
  );
}
