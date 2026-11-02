"use client";

import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { problem } from "@/lib/format";
import type { Locale } from "@/lib/messages";
import { PLAN_NAMES, signOut, useMe, type Me } from "@/lib/session";

const TERMS_VERSION = "2026-11-beta";

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
        <span className="spinner" aria-hidden="true" /> Loading…
      </div>
    );
  }
  if (me === null) return <SignIn locale={locale} onSignedIn={setMe} />;
  if (me.needsProfile) return <ProfileStep onDone={setMe} onBlocked={() => setMe(null)} />;
  return <>{children(me)}</>;
}

export function AccountBar({ me, locale }: { me: Me; locale: Locale }) {
  return (
    <p className="cluster small muted" style={{ justifyContent: "flex-end" }}>
      <span>
        {me.email} · {PLAN_NAMES[me.plan]} plan
      </span>
      {me.admin && <a href={`/${locale}/admin/jobs`}>Admin</a>}
      <button type="button" className="btn btn-ghost btn-sm" onClick={signOut}>
        Sign out
      </button>
    </p>
  );
}

function SignIn({ locale, onSignedIn }: { locale: Locale; onSignedIn: (m: Me) => void }) {
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
    if (!r.ok) return setError((await problem(r)).detail);
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
    if (!r.ok) return setError((await problem(r)).detail);
    onSignedIn(await r.json());
  }

  return (
    <section className="card" aria-labelledby="signin-heading" style={{ maxInlineSize: 460, marginInline: "auto" }}>
      <h2 id="signin-heading">Sign in to make songs</h2>
      <p className="muted">No password. We email you a 6-digit code.</p>
      {failed && (
        <p className="notice notice-danger" role="alert">
          Google sign-in did not finish. Try again or use an email code.
        </p>
      )}
      {step === "email" ? (
        <form onSubmit={sendCode}>
          <div className="field">
            <label htmlFor="signin-email">Email</label>
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
            {busy ? "Sending…" : "Email me a sign-in code"}
          </button>
        </form>
      ) : (
        <form onSubmit={verify}>
          <p className="muted">
            We sent a code to <strong>{email}</strong>. It works for 10 minutes.
          </p>
          {devCode && (
            <p className="notice small" role="status">
              Local development: no email is sent, so the code is filled in for you.
            </p>
          )}
          <div className="field">
            <label htmlFor="signin-code">6-digit code</label>
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
            {busy ? "Checking…" : "Sign in"}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setStep("email")}>
            Use a different email
          </button>
        </form>
      )}
      {methods?.google && (
        <>
          <p className="muted small" style={{ textAlign: "center", marginBlock: "var(--space-4)" }}>
            or
          </p>
          <a className="btn btn-secondary btn-block" href={`/v1/auth/google/start?locale=${locale}`}>
            Sign in with Google
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

function ProfileStep({ onDone, onBlocked }: { onDone: (m: Me) => void; onBlocked: () => void }) {
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
    if (r.status === 403) return setBlocked((await problem(r)).detail);
    if (!r.ok) return setError((await problem(r)).detail);
    onDone(await r.json());
  }

  if (blocked) {
    return (
      <section className="card" style={{ maxInlineSize: 460, marginInline: "auto" }}>
        <p role="alert">{blocked} We have deleted the account details you gave us.</p>
        <button className="btn btn-secondary" type="button" onClick={onBlocked}>
          OK
        </button>
      </section>
    );
  }

  return (
    <section className="card" aria-labelledby="profile-heading" style={{ maxInlineSize: 460, marginInline: "auto" }}>
      <h2 id="profile-heading">One more step</h2>
      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="birth-date">Date of birth</label>
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
            The beta is for people aged 18 and over. We keep only the month and year.
          </p>
        </div>
        <label className="check">
          <input type="checkbox" checked={terms} onChange={(e) => setTerms(e.target.checked)} required />
          <span>I accept the beta terms and privacy policy.</span>
        </label>
        <button className="btn btn-primary" type="submit">
          Continue
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
