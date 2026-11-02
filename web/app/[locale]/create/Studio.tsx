"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { countGraphemes } from "@/lib/graphemes";
import type { Locale } from "@/lib/messages";

const MAX_PROMPT = 500;
const TERMS_VERSION = "2026-11-beta";

type Me = { id: string; email: string; needsProfile: boolean; admin: boolean };
type Methods = { email: boolean; google: boolean; devCodes: boolean };
type Take = { index: number; audioUrl: string; format: string };
type Generation = {
  id: string;
  status: "queued" | "running" | "succeeded" | "failed";
  prompt: string;
  language: string | null;
  createdAt: string;
  takes: Take[];
  error: string | null;
};

const STATUS_TEXT: Record<Generation["status"], string> = {
  queued: "Waiting for a free worker…",
  running: "Making your two takes…",
  succeeded: "Your takes are ready.",
  failed: "Something went wrong making this song. Please try again.",
};

async function detail(r: Response): Promise<string> {
  try {
    const body = await r.json();
    return body.detail ?? `Error ${r.status}`;
  } catch {
    return `Error ${r.status}`;
  }
}

export default function Studio({ locale }: { locale: Locale }) {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const [methods, setMethods] = useState<Methods | null>(null);

  const loadMe = useCallback(async () => {
    const r = await fetch("/v1/me", { cache: "no-store" });
    setMe(r.ok ? await r.json() : null);
  }, []);

  useEffect(() => {
    loadMe();
    fetch("/v1/auth/methods")
      .then((r) => (r.ok ? r.json() : null))
      .then(setMethods)
      .catch(() => setMethods(null));
  }, [loadMe]);

  if (me === undefined) return <p aria-busy="true">Loading…</p>;
  if (me === null) return <SignIn locale={locale} methods={methods} onSignedIn={setMe} />;
  if (me.needsProfile) return <ProfileStep onDone={setMe} onBlocked={() => setMe(null)} />;
  return <Maker me={me} onSignedOut={() => setMe(null)} locale={locale} />;
}

function SignIn({ locale, methods, onSignedIn }: { locale: Locale; methods: Methods | null; onSignedIn: (m: Me) => void }) {
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"email" | "code">("email");
  const [devCode, setDevCode] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(new URLSearchParams(window.location.search).get("signin") === "failed");
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
    if (!r.ok) return setError(await detail(r));
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
    if (!r.ok) return setError(await detail(r));
    onSignedIn(await r.json());
  }

  return (
    <section aria-labelledby="signin-heading">
      <h2 id="signin-heading">Sign in</h2>
      {failed && <p role="alert">Google sign-in did not finish. Try again or use an email code.</p>}
      {step === "email" ? (
        <form onSubmit={sendCode}>
          <label>
            Email
            <input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <button type="submit" disabled={busy}>{busy ? "Sending…" : "Email me a sign-in code"}</button>
        </form>
      ) : (
        <form onSubmit={verify}>
          <p className="note">We sent a 6-digit code to {email}. It works for 10 minutes.</p>
          {devCode && (
            <p className="dev" role="status">
              Local development: no email is sent, so the code is filled in for you.
            </p>
          )}
          <label>
            Code
            <input
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              required
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </label>
          <button type="submit" disabled={busy}>{busy ? "Checking…" : "Sign in"}</button>
          <button type="button" className="link" onClick={() => setStep("email")}>Use a different email</button>
        </form>
      )}
      {methods?.google && (
        <p>
          <a className="button-link" href={`/v1/auth/google/start?locale=${locale}`}>Sign in with Google</a>
        </p>
      )}
      {error && <p role="alert">{error}</p>}
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
    if (r.status === 403) {
      setBlocked(await detail(r));
      return;
    }
    if (!r.ok) return setError(await detail(r));
    onDone(await r.json());
  }

  if (blocked) {
    return (
      <section>
        <p role="alert">{blocked} We have deleted the account details you gave us.</p>
        <button type="button" onClick={onBlocked}>OK</button>
      </section>
    );
  }

  return (
    <section aria-labelledby="profile-heading">
      <h2 id="profile-heading">One more step</h2>
      <form onSubmit={submit}>
        <label>
          Date of birth
          <input type="date" required value={birthDate} onChange={(e) => setBirthDate(e.target.value)} />
        </label>
        <p className="note">The beta is for people aged 18 and over.</p>
        <label className="check">
          <input type="checkbox" checked={terms} onChange={(e) => setTerms(e.target.checked)} required />
          <span>I accept the beta terms and privacy policy.</span>
        </label>
        <button type="submit">Continue</button>
      </form>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}

function Maker({ me, onSignedOut, locale }: { me: Me; onSignedOut: () => void; locale: Locale }) {
  const [prompt, setPrompt] = useState("");
  const [language, setLanguage] = useState(locale === "hi" ? "hi" : "en");
  const [duration, setDuration] = useState(60);
  const [instrumental, setInstrumental] = useState(false);
  const [error, setError] = useState("");
  const [songs, setSongs] = useState<Generation[]>([]);
  const streams = useRef(new Map<string, EventSource>());
  const used = countGraphemes(prompt);

  const follow = useCallback((id: string) => {
    if (streams.current.has(id)) return;
    const source = new EventSource(`/v1/generations/${id}/events`);
    streams.current.set(id, source);
    source.addEventListener("status", (event) => {
      const g: Generation = JSON.parse((event as MessageEvent).data);
      setSongs((list) => list.map((s) => (s.id === g.id ? g : s)));
      if (g.status === "succeeded" || g.status === "failed") {
        source.close();
        streams.current.delete(id);
      }
    });
    source.onerror = () => {
      // The browser reconnects on its own; a finished job ends the stream above.
    };
  }, []);

  useEffect(() => {
    const open = streams.current;
    fetch("/v1/generations", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : []))
      .then((list: Generation[]) => {
        setSongs(list);
        list.filter((g) => g.status === "queued" || g.status === "running").forEach((g) => follow(g.id));
      });
    return () => open.forEach((s) => s.close());
  }, [follow]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError("");
    const r = await fetch("/v1/generations", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ kind: "song", prompt, language, instrumental, durationSeconds: duration }),
    });
    if (!r.ok) return setError(await detail(r));
    const g: Generation = await r.json();
    setSongs((list) => [g, ...list]);
    follow(g.id);
  }

  async function signOut() {
    await fetch("/v1/auth/logout", { method: "POST" });
    onSignedOut();
  }

  return (
    <>
      <p className="who">
        Signed in as {me.email}.{" "}
        {me.admin && <a href={`/${locale}/admin/jobs`}>Failed jobs</a>}{" "}
        <button type="button" className="link" onClick={signOut}>Sign out</button>
      </p>
      <section aria-labelledby="make-heading">
        <h2 id="make-heading">Describe your song</h2>
        <form onSubmit={create}>
          <label>
            Vibe, genre, mood and instruments
            <textarea
              rows={4}
              required
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="Rainy evening lofi with soft Hindi female vocals, flute and tabla"
              aria-describedby="prompt-count"
            />
          </label>
          <p id="prompt-count" className={used > MAX_PROMPT ? "over" : "note"}>
            {used} of {MAX_PROMPT} characters
          </p>
          <div className="row">
            <label>
              Vocal language
              <select value={language} onChange={(e) => setLanguage(e.target.value)}>
                <option value="hi">Hindi</option>
                <option value="hinglish">Hinglish</option>
                <option value="en">English</option>
              </select>
            </label>
            <label>
              Length
              <select value={duration} onChange={(e) => setDuration(Number(e.target.value))}>
                <option value={30}>30 seconds</option>
                <option value={60}>1 minute</option>
                <option value={120}>2 minutes</option>
                <option value={180}>3 minutes</option>
              </select>
            </label>
          </div>
          <label className="check">
            <input type="checkbox" checked={instrumental} onChange={(e) => setInstrumental(e.target.checked)} />
            <span>Instrumental (no vocals)</span>
          </label>
          <button type="submit" disabled={used === 0 || used > MAX_PROMPT}>Make two takes</button>
        </form>
        {error && <p role="alert">{error}</p>}
        <p className="note">
          Running locally without a GPU? The stub worker returns short test tones instead of music.
        </p>
      </section>

      {songs.length > 0 && (
        <section aria-labelledby="songs-heading">
          <h2 id="songs-heading">Your songs</h2>
          <ul className="songs">
            {songs.map((g) => (
              <li key={g.id} className="song">
                <p className="prompt">{g.prompt}</p>
                <p className="status" role="status" aria-live="polite">{STATUS_TEXT[g.status]}</p>
                {g.takes.map((t) => (
                  <figure key={t.index}>
                    <figcaption>
                      Take {t.index} <span className="ai-label">AI-generated</span>
                    </figcaption>
                    <audio controls preload="none" src={t.audioUrl} />
                  </figure>
                ))}
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
