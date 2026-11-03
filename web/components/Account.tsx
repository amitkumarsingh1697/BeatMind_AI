"use client";

import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import { signOut, useMe, type Me } from "@/lib/session";

import { TERMS_VERSION } from "@/lib/terms";

type Methods = { email: boolean; google: boolean; devCodes: boolean };

/**
 * Shows the page only to a signed-in adult account: sign-in by email code or Google first, then
 * the 18+ profile step (US-009, REQ-F-53). Codes can be pasted (UX-A11Y-13).
 */
export default function RequireAccount({ locale, children }: { locale: Locale; children: (me: Me) => ReactNode }) {
  const { me, setMe } = useMe();
  if (me === undefined) {
    return (
      <div className="cluster muted" aria-busy="true">
        <span className="spinner" aria-hidden="true" /> {appText(locale).common.loading}
      </div>
    );
  }
  if (me === null) return <SignIn locale={locale} onSignedIn={setMe} />;
  if (me.needsProfile) return <ProfileStep locale={locale} onDone={setMe} onBlocked={() => setMe(null)} />;
  return <>{children(me)}</>;
}

export function AccountBar({ me, locale }: { me: Me; locale: Locale }) {
  const t = appText(locale);
  return (
    <>
      <p className="cluster small muted" style={{ justifyContent: "flex-end" }}>
        <span>
          {me.email} · {t.account.plan(t.plans[me.plan])}
        </span>
        <a className="btn btn-ghost btn-sm" href={`/${locale}/settings`}>
          {t.settings.link}
        </a>
        {me.admin && <a href={`/${locale}/admin`}>{t.account.admin}</a>}
        <button type="button" className="btn btn-ghost btn-sm" onClick={signOut}>
          {t.account.signOut}
        </button>
      </p>
      {me.suspended && (
        <p className="notice notice-danger" role="status">
          {t.account.suspended}
        </p>
      )}
    </>
  );
}

function SignIn({ locale, onSignedIn }: { locale: Locale; onSignedIn: (m: Me) => void }) {
  const t = appText(locale).account;
  const common = appText(locale).common;
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"email" | "code">("email");
  const [devCode, setDevCode] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [methods, setMethods] = useState<Methods | null>(null);

  useEffect(() => {
    setFailed(new URLSearchParams(window.location.search).get("signin") === "failed");
    fetch("/v1/auth/methods")
      .then((r) => (r.ok ? r.json() : null))
      .then(setMethods)
      .catch(() => setMethods(null));
  }, []);

  async function sendCode(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const r = await fetch("/v1/auth/email/start", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email, locale }),
    });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    const body = await r.json();
    setDevCode(body.devCode ?? null);
    if (body.devCode) setCode(body.devCode);
    setStep("code");
  }

  async function verify(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const r = await fetch("/v1/auth/email/verify", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email, code }),
    });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    onSignedIn(await r.json());
  }

  return (
    <section className="card" aria-labelledby="signin-heading" style={{ maxInlineSize: 460, marginInline: "auto" }}>
      <h2 id="signin-heading">{t.signInHeading}</h2>
      <p className="muted">{t.signInIntro}</p>
      {failed && (
        <p className="notice notice-danger" role="alert">
          {t.googleFailed}
        </p>
      )}
      {step === "email" ? (
        <form onSubmit={sendCode}>
          <div className="field">
            <label htmlFor="signin-email">{t.email}</label>
            <input
              id="signin-email"
              className="input"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <button className="btn btn-primary btn-block" type="submit" disabled={busy}>
            {busy ? common.sending : t.sendCode}
          </button>
        </form>
      ) : (
        <form onSubmit={verify}>
          <p className="muted">{t.codeSent(email)}</p>
          {devCode && (
            <p className="notice small" role="status">
              {t.devCode}
            </p>
          )}
          <div className="field">
            <label htmlFor="signin-code">{t.code}</label>
            <input
              id="signin-code"
              className="input"
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              required
              value={code}
              onChange={(e) => setCode(e.target.value.trim())}
            />
          </div>
          <button className="btn btn-primary btn-block" type="submit" disabled={busy}>
            {busy ? t.checking : t.signIn}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setStep("email")}>
            {t.otherEmail}
          </button>
        </form>
      )}
      {methods?.google && (
        <>
          <p className="muted small" style={{ textAlign: "center", marginBlock: "var(--space-4)" }}>
            {t.or}
          </p>
          <a className="btn btn-secondary btn-block" href={`/v1/auth/google/start?locale=${locale}`}>
            {t.google}
          </a>
        </>
      )}
      {error && (
        <p className="notice notice-danger" role="alert" style={{ marginBlockStart: "var(--space-4)" }}>
          {error}
        </p>
      )}
    </section>
  );
}

function ProfileStep({ locale, onDone, onBlocked }: { locale: Locale; onDone: (m: Me) => void; onBlocked: () => void }) {
  const t = appText(locale).account;
  const [birthDate, setBirthDate] = useState("");
  const [terms, setTerms] = useState(false);
  const [error, setError] = useState("");
  const [blocked, setBlocked] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError("");
    const r = await fetch("/v1/me/profile", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ birthDate, acceptTerms: terms, termsVersion: TERMS_VERSION }),
    });
    if (r.status === 403) return setBlocked(errorText(locale, await problem(r)));
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    onDone(await r.json());
  }

  if (blocked) {
    return (
      <section className="card" style={{ maxInlineSize: 460, marginInline: "auto" }}>
        <p role="alert">{blocked} {t.deleted}</p>
        <button className="btn btn-secondary" type="button" onClick={onBlocked}>
          {appText(locale).common.ok}
        </button>
      </section>
    );
  }

  return (
    <section className="card" aria-labelledby="profile-heading" style={{ maxInlineSize: 460, marginInline: "auto" }}>
      <h2 id="profile-heading">{t.profileHeading}</h2>
      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="birth-date">{t.birthDate}</label>
          <input
            id="birth-date"
            className="input"
            type="date"
            required
            value={birthDate}
            onChange={(e) => setBirthDate(e.target.value)}
            aria-describedby="birth-hint"
          />
          <p id="birth-hint" className="hint">
            {t.birthHint}
          </p>
        </div>
        <label className="check">
          <input type="checkbox" checked={terms} onChange={(e) => setTerms(e.target.checked)} required />
          <span>
            {t.acceptBefore}
            <a href={`/${locale}/terms`} target="_blank" rel="noopener">
              {t.termsLink}
            </a>
            {t.acceptAfter}
          </span>
        </label>
        <button className="btn btn-primary" type="submit">
          {t.continue}
        </button>
      </form>
      {error && (
        <p className="notice notice-danger" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
