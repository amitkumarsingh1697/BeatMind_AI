"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import RequireAccount, { AccountBar } from "@/components/Account";
import AiLabel from "@/components/AiLabel";
import { MusicIcon, PenIcon, SparkleIcon } from "@/components/Icons";
import Player from "@/components/Player";
import { languageTag, problem } from "@/lib/format";
import { countGraphemes } from "@/lib/graphemes";
import type { Locale } from "@/lib/messages";
import type { Me } from "@/lib/session";
import { useTurnstile } from "@/lib/useTurnstile";

const MAX_PROMPT = 500;
const MAX_LYRICS = 3000;
const MAX_TITLE = 100;
const SETTINGS_KEY = "bm_create_settings";
const EXAMPLES = ["Chill rain vlog with soft piano", "Shaadi dance with dhol and shehnai", "Motivational gym track, hard drums"];

type Mode = "vibe" | "lyrics";
type Take = { index: number; status: string; audioUrl: string | null; format: string | null };
type Generation = {
  id: string;
  songId: string | null;
  status: "queued" | "running" | "succeeded" | "failed";
  title: string | null;
  prompt: string;
  takes: Take[];
  refundedCredits: number;
  error: string | null;
};
type Allowance = { plan: string; window: "day" | "month"; limit: number; used: number; remaining: number; creditsPerRequest: number; resetsAt: string };
type Settings = { mode: Mode; language: string; duration: number; vocalType: string; instrumental: boolean };

const STATUS_TEXT: Record<Generation["status"], string> = {
  queued: "Waiting for a free studio…",
  running: "Making your two takes…",
  succeeded: "Your takes are ready.",
  failed: "We could not make this song.",
};

function loadSettings(locale: Locale): Settings {
  const fallback: Settings = { mode: "vibe", language: locale === "hi" ? "hi" : "en", duration: 60, vocalType: "any", instrumental: false };
  try {
    return { ...fallback, ...JSON.parse(window.localStorage.getItem(SETTINGS_KEY) ?? "{}") };
  } catch {
    return fallback;
  }
}

function saveSettings(s: Settings) {
  try {
    window.localStorage.setItem(SETTINGS_KEY, JSON.stringify(s));
  } catch {
    // Private mode: settings are simply not remembered.
  }
}

export default function Studio({ locale }: { locale: Locale }) {
  return <RequireAccount locale={locale}>{(me) => <Maker me={me} locale={locale} />}</RequireAccount>;
}

function Maker({ me, locale }: { me: Me; locale: Locale }) {
  const [settings, setSettings] = useState<Settings>(() => loadSettings(locale));
  const [prompt, setPrompt] = useState("");
  const [lyrics, setLyrics] = useState("");
  const [title, setTitle] = useState("");
  const [error, setError] = useState<{ detail: string; field: string | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const [songs, setSongs] = useState<Generation[]>([]);
  const [allowance, setAllowance] = useState<Allowance | null>(null);
  const streams = useRef(new Map<string, EventSource>());
  const turnstile = useTurnstile();
  const { mode } = settings;
  const promptCount = countGraphemes(prompt);
  const lyricsCount = countGraphemes(lyrics);
  const titleCount = countGraphemes(title);

  const update = (patch: Partial<Settings>) =>
    setSettings((s) => {
      const next = { ...s, ...patch };
      saveSettings(next);
      return next;
    });

  const loadAllowance = useCallback(() => {
    fetch("/v1/credits", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((body) => setAllowance(body?.allowance ?? null))
      .catch(() => setAllowance(null));
  }, []);

  const follow = useCallback(
    (id: string) => {
      if (streams.current.has(id)) return;
      const source = new EventSource(`/v1/generations/${id}/events`);
      streams.current.set(id, source);
      source.addEventListener("status", (event) => {
        const g: Generation = JSON.parse((event as MessageEvent).data);
        setSongs((list) => list.map((s) => (s.id === g.id ? g : s)));
        if (g.status === "succeeded" || g.status === "failed") {
          source.close();
          streams.current.delete(id);
          loadAllowance();
        }
      });
    },
    [loadAllowance],
  );

  useEffect(() => {
    const open = streams.current;
    loadAllowance();
    fetch("/v1/generations", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : []))
      .then((list: Generation[]) => {
        setSongs(list.slice(0, 5));
        list.filter((g) => g.status === "queued" || g.status === "running").forEach((g) => follow(g.id));
      });
    return () => open.forEach((s) => s.close());
  }, [follow, loadAllowance]);

  const overLimit =
    promptCount > MAX_PROMPT || titleCount > MAX_TITLE || (mode === "lyrics" ? lyricsCount > MAX_LYRICS || lyricsCount === 0 : promptCount === 0);
  const noCredits = allowance !== null && allowance.remaining < allowance.creditsPerRequest;

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    const body: Record<string, unknown> = {
      kind: "song",
      mode,
      prompt,
      language: settings.language,
      durationSeconds: settings.duration,
      turnstileToken: turnstile.token || undefined,
    };
    if (mode === "lyrics") {
      body.lyrics = lyrics;
      body.title = title || undefined;
    } else {
      body.instrumental = settings.instrumental;
    }
    if (!(mode === "vibe" && settings.instrumental) && settings.vocalType !== "any") body.vocalType = settings.vocalType;
    const r = await fetch("/v1/generations", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    setBusy(false);
    turnstile.reset();
    if (!r.ok) {
      const p = await problem(r);
      const field = p.code?.startsWith("invalid_") ? p.code.slice("invalid_".length) : null;
      setError({ detail: p.detail, field });
      loadAllowance();
      return;
    }
    const g: Generation = await r.json();
    setSongs((list) => [g, ...list].slice(0, 5));
    follow(g.id);
    loadAllowance();
  }

  const fieldError = (name: string) => (error?.field === name ? error.detail : null);

  return (
    <div className="stack-lg">
      <AccountBar me={me} locale={locale} />
      <header className="page-head">
        <p className="eyebrow">Create</p>
        <h1>Make a song</h1>
        <p>Describe a vibe or bring your own lyrics. You get two takes to choose from.</p>
      </header>

      {allowance && <CreditMeter allowance={allowance} />}

      <section className="card" aria-labelledby="make-heading">
        <h2 id="make-heading" className="visually-hidden">
          Song details
        </h2>
        <form onSubmit={create} noValidate>
          <fieldset className="segmented">
            <legend className="visually-hidden">How do you want to start?</legend>
            <label>
              <input type="radio" name="mode" value="vibe" checked={mode === "vibe"} onChange={() => update({ mode: "vibe" })} />
              <span className="cluster">
                <SparkleIcon size={18} /> Describe a vibe
              </span>
            </label>
            <label>
              <input type="radio" name="mode" value="lyrics" checked={mode === "lyrics"} onChange={() => update({ mode: "lyrics" })} />
              <span className="cluster">
                <PenIcon size={18} /> Use my lyrics
              </span>
            </label>
          </fieldset>

          {mode === "vibe" ? (
            <div className="field">
              <label htmlFor="prompt">Vibe, genre, mood and instruments</label>
              <textarea
                id="prompt"
                className="textarea"
                rows={4}
                value={prompt}
                onChange={(e) => setPrompt(e.target.value)}
                placeholder="Rainy evening lofi with soft Hindi female vocals, flute and tabla"
                aria-describedby="prompt-count prompt-error"
                aria-invalid={promptCount > MAX_PROMPT || fieldError("prompt") !== null}
              />
              <div className="spread">
                <div className="chips" aria-label="Examples">
                  {EXAMPLES.map((ex) => (
                    <button key={ex} type="button" className="chip" onClick={() => setPrompt(ex)}>
                      {ex}
                    </button>
                  ))}
                </div>
                <p id="prompt-count" className={promptCount > MAX_PROMPT ? "counter over" : "counter"}>
                  {promptCount} / {MAX_PROMPT}
                </p>
              </div>
              {fieldError("prompt") && (
                <p id="prompt-error" className="error">
                  {fieldError("prompt")}
                </p>
              )}
            </div>
          ) : (
            <>
              <div className="field">
                <label htmlFor="lyrics">Your lyrics</label>
                <p id="lyrics-hint" className="hint">
                  Hindi (Devanagari), Hinglish or English. Mark sections with [Verse] and [Chorus] if you like.
                </p>
                <textarea
                  id="lyrics"
                  className="textarea lyrics"
                  value={lyrics}
                  lang={languageTag(settings.language)}
                  onChange={(e) => setLyrics(e.target.value)}
                  placeholder={"[Verse]\nRaat ka safar, tere bina…\n\n[Chorus]\n…"}
                  aria-describedby="lyrics-hint lyrics-count lyrics-error"
                  aria-invalid={lyricsCount > MAX_LYRICS || fieldError("lyrics") !== null}
                />
                <p id="lyrics-count" className={lyricsCount > MAX_LYRICS ? "counter over" : "counter"}>
                  {lyricsCount.toLocaleString("en-IN")} / {MAX_LYRICS.toLocaleString("en-IN")} characters
                </p>
                {fieldError("lyrics") && (
                  <p id="lyrics-error" className="error">
                    {fieldError("lyrics")}
                  </p>
                )}
              </div>
              <div className="grid-2">
                <div className="field">
                  <label htmlFor="style">Style</label>
                  <input
                    id="style"
                    className="input"
                    value={prompt}
                    onChange={(e) => setPrompt(e.target.value)}
                    placeholder="Slow acoustic ballad, guitar and strings"
                    aria-describedby="style-count"
                    aria-invalid={promptCount > MAX_PROMPT || fieldError("prompt") !== null}
                  />
                  <p id="style-count" className={promptCount > MAX_PROMPT ? "counter over" : "counter"}>
                    {promptCount} / {MAX_PROMPT}
                  </p>
                </div>
                <div className="field">
                  <label htmlFor="title">Title (optional)</label>
                  <input
                    id="title"
                    className="input"
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    placeholder="From your first line if empty"
                    aria-invalid={titleCount > MAX_TITLE || fieldError("title") !== null}
                    aria-describedby="title-error"
                  />
                  {fieldError("title") && (
                    <p id="title-error" className="error">
                      {fieldError("title")}
                    </p>
                  )}
                </div>
              </div>
            </>
          )}

          <div className="grid-2">
            <div className="field">
              <label htmlFor="language">Vocal language</label>
              <select id="language" className="select" value={settings.language} onChange={(e) => update({ language: e.target.value })}>
                <option value="hi">Hindi</option>
                <option value="hinglish">Hinglish</option>
                <option value="en">English</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="length">Length</label>
              <select id="length" className="select" value={settings.duration} onChange={(e) => update({ duration: Number(e.target.value) })}>
                <option value={30}>30 seconds</option>
                <option value={60}>1 minute</option>
                <option value={120}>2 minutes</option>
                <option value={180}>3 minutes</option>
                <option value={240}>4 minutes</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="voice">Voice</label>
              <select
                id="voice"
                className="select"
                value={settings.vocalType}
                disabled={mode === "vibe" && settings.instrumental}
                onChange={(e) => update({ vocalType: e.target.value })}
              >
                <option value="any">Let BeatMind choose</option>
                <option value="female">Female</option>
                <option value="male">Male</option>
                <option value="duet">Duet</option>
              </select>
            </div>
            {mode === "vibe" && (
              <label className="check" style={{ alignSelf: "end", minBlockSize: "var(--tap)" }}>
                <input type="checkbox" checked={settings.instrumental} onChange={(e) => update({ instrumental: e.target.checked })} />
                <span>Instrumental (no vocals)</span>
              </label>
            )}
          </div>

          {turnstile.enabled && <div ref={turnstile.ref} />}

          {error && !error.field && (
            <div className="notice notice-danger" role="alert">
              {error.detail}
            </div>
          )}
          {error && error.field && (
            <p className="visually-hidden" role="alert">
              {error.detail}
            </p>
          )}

          <div className="spread">
            <p className="small muted" style={{ margin: 0 }}>
              Every song is labelled as AI-generated.
            </p>
            <button className="btn btn-primary btn-lg" type="submit" disabled={busy || overLimit || noCredits}>
              {busy ? (
                <>
                  <span className="spinner" aria-hidden="true" /> Sending…
                </>
              ) : (
                <>
                  Make two takes <span className="cost">· 2 credits</span>
                </>
              )}
            </button>
          </div>
        </form>
      </section>

      <RecentSongs songs={songs} locale={locale} />
    </div>
  );
}

function CreditMeter({ allowance }: { allowance: Allowance }) {
  const left = allowance.remaining;
  const pct = allowance.limit > 0 ? (left / allowance.limit) * 100 : 0;
  const period = allowance.window === "day" ? "today" : "this month";
  return (
    <section className="card tight flat meter" aria-label="Credits">
      <div className="spread">
        <strong>
          {left} of {allowance.limit} credits left {period}
        </strong>
        <span className="small muted">1 credit = 1 take · failed takes are refunded</span>
      </div>
      <div className="meter-track" aria-hidden="true">
        <div className="meter-fill" style={{ inlineSize: `${pct}%` }} />
      </div>
      {left < allowance.creditsPerRequest && (
        <p className="small" role="status" style={{ margin: 0 }}>
          {allowance.window === "day"
            ? "You have used today's free songs. Your credits come back at midnight India time."
            : "You have used this month's credits."}
        </p>
      )}
    </section>
  );
}

function RecentSongs({ songs, locale }: { songs: Generation[]; locale: Locale }) {
  if (songs.length === 0) {
    return (
      <div className="empty">
        <MusicIcon size={32} />
        <h2>Your songs will appear here</h2>
        <p>Each request makes two takes in about a minute. Keep the one you like.</p>
      </div>
    );
  }
  return (
    <section aria-labelledby="recent-heading" className="stack">
      <div className="spread">
        <h2 id="recent-heading" style={{ margin: 0 }}>
          Latest songs
        </h2>
        <a href={`/${locale}/library`}>See all in your library</a>
      </div>
      <ul className="song-list">
        {songs.map((g) => {
          const title = g.title ?? g.prompt;
          const ready = g.takes.filter((t) => t.audioUrl);
          return (
            <li key={g.id} className="card tight flat stack">
              <div className="spread">
                <p className="player-title" style={{ fontFamily: "var(--font-display)", fontSize: "var(--step-1)" }}>
                  {g.songId ? <a href={`/${locale}/songs/${g.songId}`}>{title}</a> : title}
                </p>
                <AiLabel />
              </div>
              <p className="cluster small muted" role="status" aria-live="polite" style={{ margin: 0 }}>
                {(g.status === "queued" || g.status === "running") && <span className="spinner" aria-hidden="true" />}
                {STATUS_TEXT[g.status]}
                {g.status === "failed" && g.refundedCredits > 0 && ` We refunded ${g.refundedCredits} credits.`}
                {g.status === "succeeded" && g.refundedCredits > 0 && ` One take was held back and its credit refunded.`}
              </p>
              {ready.length > 0 && (
                <div className="grid-2">
                  {ready.map((t) => (
                    <Player key={t.index} src={t.audioUrl!} title={title} takeLabel={`Take ${t.index}`} compact />
                  ))}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
