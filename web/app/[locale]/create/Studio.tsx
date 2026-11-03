"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import RequireAccount, { AccountBar } from "@/components/Account";
import AiLabel from "@/components/AiLabel";
import Blocked from "@/components/Blocked";
import { MusicIcon, PenIcon, SparkleIcon, WaveIcon } from "@/components/Icons";
import Player from "@/components/Player";
import { count, languageTag, problem, spokenTime, type Problem } from "@/lib/format";
import { appText, errorText, type AppText } from "@/lib/i18n";
import { countGraphemes } from "@/lib/graphemes";
import type { Locale } from "@/lib/messages";
import type { Me } from "@/lib/session";
import { useTurnstile } from "@/lib/useTurnstile";

const MAX_PROMPT = 500;
const MAX_LYRICS = 3000;
const MAX_TITLE = 100;
const SETTINGS_KEY = "bm_create_settings";

type Mode = "vibe" | "lyrics" | "beat";
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
  queuePosition: number | null;
  estimatedSeconds: number | null;
};
type Allowance = { plan: string; window: "day" | "month"; limit: number; used: number; remaining: number; creditsPerRequest: number; resetsAt: string };
type Settings = {
  mode: Mode;
  language: string;
  duration: number;
  vocalType: string;
  instrumental: boolean;
  genre: string;
  bpm: number;
  tonic: string;
  quality: "major" | "minor";
  include: string[];
  exclude: string[];
};
type Language = { code: string; languageTag: string; experimental: boolean };
type Draft = { id: string; status: "writing" | "ready" | "blocked" | "failed"; lyrics: string | null; languageTag: string; message: string | null };

const LANGUAGE_CODES = ["hi", "hinglish", "en"];
// Beat mode choices (US-015); the API checks the same lists (SongRequests).
const GENRES = ["lofi", "hip_hop", "trap", "boom_bap", "drill", "house", "techno", "edm", "pop", "rnb", "afrobeats", "reggaeton", "bollywood", "bhangra", "indian_fusion", "ambient", "cinematic", "jazz", "rock", "funk"];
const INSTRUMENTS = ["drums", "808", "bass", "piano", "keys", "guitar", "synth", "pads", "strings", "brass", "flute", "tabla", "dhol", "sitar", "sarangi", "harmonium", "shehnai", "santoor", "percussion"];
const TONICS = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"];
const MIN_BPM = 60;
const MAX_BPM = 200;

function loadSettings(locale: Locale): Settings {
  const fallback: Settings = {
    mode: "vibe",
    language: locale === "hi" ? "hi" : "en",
    duration: 60,
    vocalType: "any",
    instrumental: false,
    genre: "lofi",
    bpm: 90,
    tonic: "any",
    quality: "minor",
    include: [],
    exclude: [],
  };
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
  const [blocked, setBlocked] = useState<Problem | null>(null);
  const [draftId, setDraftId] = useState<string | null>(null);
  const [languages, setLanguages] = useState<Language[]>([]);
  const lyricsRef = useRef<HTMLTextAreaElement>(null);
  const t = appText(locale);
  const c = t.create;
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
    fetch("/v1/languages", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : []))
      .then(setLanguages)
      .catch(() => setLanguages([]));
    fetch("/v1/generations", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : []))
      .then((list: Generation[]) => {
        setSongs(list.slice(0, 5));
        list.filter((g) => g.status === "queued" || g.status === "running").forEach((g) => follow(g.id));
      });
    return () => open.forEach((s) => s.close());
  }, [follow, loadAllowance]);

  const bpmOk = Number.isInteger(settings.bpm) && settings.bpm >= MIN_BPM && settings.bpm <= MAX_BPM;
  const overLimit =
    promptCount > MAX_PROMPT ||
    titleCount > MAX_TITLE ||
    (mode === "lyrics" ? lyricsCount > MAX_LYRICS || lyricsCount === 0 : mode === "beat" ? !bpmOk : promptCount === 0);
  const noCredits = allowance !== null && allowance.remaining < allowance.creditsPerRequest;

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBlocked(null);
    setBusy(true);
    const body: Record<string, unknown> = {
      kind: mode === "beat" ? "beat" : "song",
      mode,
      prompt,
      language: settings.language,
      durationSeconds: settings.duration,
      turnstileToken: turnstile.token || undefined,
    };
    if (mode === "lyrics") {
      body.lyrics = lyrics;
      body.title = title || undefined;
      body.lyricsDraftId = draftId ?? undefined;
    } else if (mode === "beat") {
      body.title = title || undefined;
      body.genre = settings.genre;
      body.bpm = settings.bpm;
      body.key = settings.tonic === "any" ? "any" : `${settings.tonic} ${settings.quality}`;
      body.includeInstruments = settings.include;
      body.excludeInstruments = settings.exclude;
    } else {
      body.instrumental = settings.instrumental;
    }
    if (mode !== "beat" && !(mode === "vibe" && settings.instrumental) && settings.vocalType !== "any") body.vocalType = settings.vocalType;
    const r = await fetch("/v1/generations", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    setBusy(false);
    turnstile.reset();
    if (!r.ok) {
      const p = await problem(r);
      if (p.code?.startsWith("moderation_")) {
        setBlocked(p);
        return;
      }
      const field = p.code?.startsWith("invalid_") ? p.code.slice("invalid_".length) : null;
      setError({ detail: errorText(locale, p), field });
      loadAllowance();
      return;
    }
    const g: Generation = await r.json();
    setSongs((list) => [g, ...list].slice(0, 5));
    follow(g.id);
    loadAllowance();
  }

  const fieldError = (name: string) => (error?.field === name ? error.detail : null);
  const experimental = (code: string) => languages.find((l) => l.code === code)?.experimental ?? false;

  function useLyrics(text: string, id: string) {
    setLyrics(text);
    setDraftId(id);
    // Back to the lyrics box so the creator can edit them (UX-A11Y-05: focus moves on purpose).
    window.setTimeout(() => lyricsRef.current?.focus(), 0);
  }

  return (
    <div className="stack-lg">
      <AccountBar me={me} locale={locale} />
      <header className="page-head">
        <p className="eyebrow">{c.eyebrow}</p>
        <h1>{c.heading}</h1>
        <p>{c.intro}</p>
      </header>

      {allowance && <CreditMeter allowance={allowance} t={t} />}

      <section className="card" aria-labelledby="make-heading">
        <h2 id="make-heading" className="visually-hidden">
          {c.details}
        </h2>
        <form onSubmit={create} noValidate>
          <fieldset className="segmented">
            <legend className="visually-hidden">{c.startHow}</legend>
            <label>
              <input type="radio" name="mode" value="vibe" checked={mode === "vibe"} onChange={() => update({ mode: "vibe" })} />
              <span className="cluster">
                <SparkleIcon size={18} /> {c.vibe}
              </span>
            </label>
            <label>
              <input type="radio" name="mode" value="lyrics" checked={mode === "lyrics"} onChange={() => update({ mode: "lyrics" })} />
              <span className="cluster">
                <PenIcon size={18} /> {c.lyrics}
              </span>
            </label>
            <label>
              <input type="radio" name="mode" value="beat" checked={mode === "beat"} onChange={() => update({ mode: "beat" })} />
              <span className="cluster">
                <WaveIcon size={18} /> {c.beat}
              </span>
            </label>
          </fieldset>

          {mode === "beat" ? (
            <BeatFields
              t={t}
              settings={settings}
              update={update}
              prompt={prompt}
              setPrompt={setPrompt}
              promptCount={promptCount}
              title={title}
              setTitle={setTitle}
              fieldError={fieldError}
            />
          ) : mode === "vibe" ? (
            <div className="field">
              <label htmlFor="prompt">{c.vibeLabel}</label>
              <textarea
                id="prompt"
                className="textarea"
                rows={4}
                value={prompt}
                onChange={(e) => setPrompt(e.target.value)}
                placeholder={c.vibePlaceholder}
                aria-describedby="prompt-count prompt-error"
                aria-invalid={promptCount > MAX_PROMPT || fieldError("prompt") !== null}
              />
              <div className="spread">
                <div className="chips" aria-label={c.examples}>
                  {c.exampleList.map((ex) => (
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
                <div className="spread">
                  <label htmlFor="lyrics">{c.lyricsLabel}</label>
                  {draftId && <AiLabel text={t.common.ai} />}
                </div>
                <p id="lyrics-hint" className="hint">
                  {c.lyricsHint}
                </p>
                <textarea
                  ref={lyricsRef}
                  id="lyrics"
                  className="textarea lyrics"
                  value={lyrics}
                  lang={languageTag(settings.language)}
                  onChange={(e) => {
                    setLyrics(e.target.value);
                    if (e.target.value.trim() === "") setDraftId(null);
                  }}
                  placeholder={c.lyricsPlaceholder}
                  aria-describedby="lyrics-hint lyrics-count lyrics-error"
                  aria-invalid={lyricsCount > MAX_LYRICS || fieldError("lyrics") !== null}
                />
                <p id="lyrics-count" className={lyricsCount > MAX_LYRICS ? "counter over" : "counter"}>
                  {count(lyricsCount, t.intlLocale)} / {count(MAX_LYRICS, t.intlLocale)} {c.characters}
                </p>
                {fieldError("lyrics") && (
                  <p id="lyrics-error" className="error">
                    {fieldError("lyrics")}
                  </p>
                )}
              </div>
              <LyricWriter locale={locale} language={settings.language} onUse={useLyrics} />
              <div className="grid-2">
                <div className="field">
                  <label htmlFor="style">{c.style}</label>
                  <input
                    id="style"
                    className="input"
                    value={prompt}
                    onChange={(e) => setPrompt(e.target.value)}
                    placeholder={c.stylePlaceholder}
                    aria-describedby="style-count"
                    aria-invalid={promptCount > MAX_PROMPT || fieldError("prompt") !== null}
                  />
                  <p id="style-count" className={promptCount > MAX_PROMPT ? "counter over" : "counter"}>
                    {promptCount} / {MAX_PROMPT}
                  </p>
                </div>
                <div className="field">
                  <label htmlFor="title">{c.title}</label>
                  <input
                    id="title"
                    className="input"
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    placeholder={c.titlePlaceholder}
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
            {mode !== "beat" && (
            <div className="field">
              <label htmlFor="language">{c.language}</label>
              <select
                id="language"
                className="select"
                value={settings.language}
                onChange={(e) => update({ language: e.target.value })}
                aria-describedby={experimental(settings.language) ? "language-note" : undefined}
              >
                {LANGUAGE_CODES.map((code) => (
                  <option key={code} value={code}>
                    {t.languages[code]}
                    {experimental(code) ? ` (${t.common.experimental})` : ""}
                  </option>
                ))}
              </select>
              {experimental(settings.language) && (
                <p id="language-note" className="hint">
                  <span className="badge badge-accent">{t.common.experimental}</span> {c.experimentalNote}
                </p>
              )}
            </div>
            )}
            <div className="field">
              <label htmlFor="length">{c.length}</label>
              <select id="length" className="select" value={settings.duration} onChange={(e) => update({ duration: Number(e.target.value) })}>
                {[30, 60, 120, 180, 240].map((n) => (
                  <option key={n} value={n}>
                    {c.lengths[n]}
                  </option>
                ))}
              </select>
            </div>
            {mode !== "beat" && (
            <div className="field">
              <label htmlFor="voice">{c.voice}</label>
              <select
                id="voice"
                className="select"
                value={settings.vocalType}
                disabled={mode === "vibe" && settings.instrumental}
                onChange={(e) => update({ vocalType: e.target.value })}
              >
                {["any", "female", "male", "duet"].map((v) => (
                  <option key={v} value={v}>
                    {c.voices[v]}
                  </option>
                ))}
              </select>
            </div>
            )}
            {mode === "vibe" && (
              <label className="check" style={{ alignSelf: "end", minBlockSize: "var(--tap)" }}>
                <input type="checkbox" checked={settings.instrumental} onChange={(e) => update({ instrumental: e.target.checked })} />
                <span>{c.instrumental}</span>
              </label>
            )}
          </div>

          {turnstile.enabled && <div ref={turnstile.ref} />}

          {blocked && <Blocked key={blocked.caseId ?? "blocked"} locale={locale} p={blocked} onClose={() => setBlocked(null)} />}

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
              {c.labelNote}
            </p>
            <button className="btn btn-primary btn-lg" type="submit" disabled={busy || overLimit || noCredits}>
              {busy ? (
                <>
                  <span className="spinner" aria-hidden="true" /> {t.common.sending}
                </>
              ) : (
                <>
                  {c.make} <span className="cost">{c.cost}</span>
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

function CreditMeter({ allowance, t }: { allowance: Allowance; t: AppText }) {
  const c = t.create;
  const left = allowance.remaining;
  const pct = allowance.limit > 0 ? (left / allowance.limit) * 100 : 0;
  return (
    <section className="card tight flat meter" aria-label={c.credits}>
      <div className="spread">
        <strong>{c.creditsLeft(left, allowance.limit, allowance.window === "day")}</strong>
        <span className="small muted">{c.creditRule}</span>
      </div>
      <div className="meter-track" aria-hidden="true">
        <div className="meter-fill" style={{ inlineSize: `${pct}%` }} />
      </div>
      {left < allowance.creditsPerRequest && (
        <p className="small" role="status" style={{ margin: 0 }}>
          {allowance.window === "day" ? c.outOfDay : c.outOfMonth}
        </p>
      )}
    </section>
  );
}

function RecentSongs({ songs, locale }: { songs: Generation[]; locale: Locale }) {
  const t = appText(locale);
  const c = t.create;
  if (songs.length === 0) {
    return (
      <div className="empty">
        <MusicIcon size={32} />
        <h2>{c.emptyHeading}</h2>
        <p>{c.emptyText}</p>
      </div>
    );
  }
  return (
    <section aria-labelledby="recent-heading" className="stack">
      <div className="spread">
        <h2 id="recent-heading" style={{ margin: 0 }}>
          {c.latest}
        </h2>
        <a href={`/${locale}/library`}>{c.seeAll}</a>
      </div>
      <ul className="song-list">
        {songs.map((g) => {
          const title = g.title ?? g.prompt;
          const ready = g.takes.filter((tk) => tk.audioUrl);
          return (
            <li key={g.id} className="card tight flat stack">
              <div className="spread">
                <p className="player-title" style={{ fontFamily: "var(--font-display)", fontSize: "var(--step-1)" }}>
                  {g.songId ? <a href={`/${locale}/songs/${g.songId}`}>{title}</a> : title}
                </p>
                <AiLabel text={t.common.ai} />
              </div>
              <p className="cluster small muted" role="status" aria-live="polite" style={{ margin: 0 }}>
                {(g.status === "queued" || g.status === "running") && <span className="spinner" aria-hidden="true" />}
                {c.status[g.status]}
                {g.status === "queued" && g.queuePosition != null && ` ${c.queuePosition(g.queuePosition)}`}
                {g.status === "running" && g.queuePosition === 0 && ` ${c.running}`}
                {(g.status === "queued" || g.status === "running") &&
                  g.estimatedSeconds != null &&
                  ` ${c.estimate(spokenTime(Math.max(5, Math.round(g.estimatedSeconds / 5) * 5), t.time))}`}
                {g.status === "failed" && g.refundedCredits > 0 && c.refunded(g.refundedCredits)}
                {g.status === "succeeded" && g.refundedCredits > 0 && c.heldBack}
              </p>
              {ready.length > 0 && (
                <div className="grid-2">
                  {ready.map((tk) => (
                    <Player key={tk.index} src={tk.audioUrl!} title={title} takeLabel={c.take(tk.index)} compact locale={locale} />
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

/**
 * AI lyric writer (US-018): topic and mood in, sectioned lyrics out, in the chosen vocal language.
 * Writing lyrics costs no credits. What the AI wrote is screened by the API before it is shown.
 */
function LyricWriter({ locale, language, onUse }: { locale: Locale; language: string; onUse: (lyrics: string, id: string) => void }) {
  const t = appText(locale);
  const w = t.writer;
  const [open, setOpen] = useState(false);
  const [topic, setTopic] = useState("");
  const [mood, setMood] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const timer = useRef<number | null>(null);

  useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current);
  }, []);

  function poll(id: string, wait = 1000) {
    timer.current = window.setTimeout(async () => {
      const r = await fetch(`/v1/lyrics/${id}`, { cache: "no-store" });
      if (!r.ok) {
        setBusy(false);
        return setError(await problem(r));
      }
      const d: Draft = await r.json();
      setDraft(d);
      if (d.status === "writing") poll(id, Math.min(wait * 1.5, 4000));
      else setBusy(false);
    }, wait);
  }

  async function write() {
    if (busy || topic.trim() === "") return;
    setError(null);
    setDraft(null);
    setBusy(true);
    const r = await fetch("/v1/lyrics", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ topic, mood: mood || undefined, language }),
    });
    if (!r.ok) {
      setBusy(false);
      return setError(await problem(r));
    }
    const d: Draft = await r.json();
    setDraft(d);
    poll(d.id);
  }

  if (!open) {
    return (
      <button type="button" className="btn btn-secondary" onClick={() => setOpen(true)} aria-expanded={false}>
        <SparkleIcon size={18} /> {w.open}
      </button>
    );
  }

  return (
    <section className="card flat stack writer" aria-labelledby="writer-heading">
      <div className="spread">
        <h3 id="writer-heading" style={{ margin: 0 }}>
          {w.heading}
        </h3>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(false)} aria-expanded={true}>
          {t.common.close}
        </button>
      </div>
      <p className="small muted" style={{ margin: 0 }}>
        {w.intro}
      </p>
      {/* A div, not a nested form: the writer sits inside the song form. */}
      <div className="grid-2" role="group" aria-labelledby="writer-heading">
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="writer-topic">{w.topic}</label>
          <input id="writer-topic" className="input" value={topic} maxLength={200} onChange={(e) => setTopic(e.target.value)} placeholder={w.topicPlaceholder} onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                write();
              }
            }} />
        </div>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="writer-mood">{w.mood}</label>
          <input id="writer-mood" className="input" value={mood} maxLength={100} onChange={(e) => setMood(e.target.value)} placeholder={w.moodPlaceholder} onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                write();
              }
            }} />
        </div>
      </div>
      <div className="cluster">
        <button type="button" className="btn btn-primary" disabled={busy || topic.trim() === ""} onClick={write}>
          {busy ? (
            <>
              <span className="spinner" aria-hidden="true" /> {w.writing}
            </>
          ) : draft?.status === "ready" ? (
            w.again
          ) : (
            w.write
          )}
        </button>
        <span className="small muted">{t.languages[language]}</span>
      </div>
      <div aria-live="polite">
        {error && (error.code?.startsWith("moderation_") ? <Blocked locale={locale} p={error} /> : <p className="error">{errorText(locale, error)}</p>)}
        {draft?.status === "blocked" && <p className="notice notice-danger">{w.blocked}</p>}
        {draft?.status === "failed" && <p className="notice notice-danger">{w.failed}</p>}
        {draft?.status === "ready" && draft.lyrics && (
          <div className="stack">
            <div className="spread">
              <AiLabel text={t.common.ai} />
              <button type="button" className="btn btn-primary btn-sm" onClick={() => onUse(draft.lyrics!, draft.id)}>
                {w.use}
              </button>
            </div>
            {/* Focusable so keyboard users can scroll long lyrics (axe scrollable-region-focusable). */}
            <pre className="lyrics-preview" lang={draft.languageTag} tabIndex={0} role="region" aria-label={w.heading}>
              {draft.lyrics}
            </pre>
            <p className="small muted" style={{ margin: 0 }}>
              {w.aiNote}
            </p>
          </div>
        )}
      </div>
    </section>
  );
}

/** Beat mode fields (US-015, REQ-F-04): genre, tempo, key and instruments to feature or leave out. */
function BeatFields({
  t,
  settings,
  update,
  prompt,
  setPrompt,
  promptCount,
  title,
  setTitle,
  fieldError,
}: {
  t: AppText;
  settings: Settings;
  update: (patch: Partial<Settings>) => void;
  prompt: string;
  setPrompt: (v: string) => void;
  promptCount: number;
  title: string;
  setTitle: (v: string) => void;
  fieldError: (name: string) => string | null;
}) {
  const c = t.create;
  const bpmError = fieldError("bpm");
  const toggle = (list: "include" | "exclude", name: string, on: boolean) => {
    const other = list === "include" ? "exclude" : "include";
    const next = on ? [...settings[list], name].slice(0, 8) : settings[list].filter((x) => x !== name);
    // An instrument cannot be in both lists.
    update({ [list]: next, [other]: settings[other].filter((x) => x !== name) } as Partial<Settings>);
  };
  return (
    <div className="stack">
      <p className="small muted" style={{ margin: 0 }}>
        {c.beatIntro}
      </p>
      <div className="grid-2">
        <div className="field">
          <label htmlFor="genre">{c.genre}</label>
          <select id="genre" className="select" value={settings.genre} onChange={(e) => update({ genre: e.target.value })} aria-invalid={fieldError("genre") !== null}>
            {GENRES.map((g) => (
              <option key={g} value={g}>
                {c.genres[g]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="bpm">{c.bpm}</label>
          <input
            id="bpm"
            className="input"
            type="number"
            inputMode="numeric"
            min={MIN_BPM}
            max={MAX_BPM}
            step={1}
            value={Number.isNaN(settings.bpm) ? "" : settings.bpm}
            onChange={(e) => update({ bpm: e.target.value === "" ? NaN : Number(e.target.value) })}
            aria-describedby="bpm-hint bpm-error"
            aria-invalid={bpmError !== null || !(settings.bpm >= MIN_BPM && settings.bpm <= MAX_BPM)}
          />
          <p id="bpm-hint" className="hint">
            {c.bpmHint}
          </p>
          {bpmError && (
            <p id="bpm-error" className="error">
              {bpmError}
            </p>
          )}
        </div>
        <fieldset className="field key-field">
          <legend>{c.key}</legend>
          <div className="cluster">
            <select aria-label={c.key} className="select" value={settings.tonic} onChange={(e) => update({ tonic: e.target.value })}>
              <option value="any">{c.anyKey}</option>
              {TONICS.map((k) => (
                <option key={k} value={k}>
                  {k}
                </option>
              ))}
            </select>
            {settings.tonic !== "any" && (
              <select aria-label={`${c.key}: ${c.major} / ${c.minor}`} className="select" value={settings.quality} onChange={(e) => update({ quality: e.target.value as "major" | "minor" })}>
                <option value="major">{c.major}</option>
                <option value="minor">{c.minor}</option>
              </select>
            )}
          </div>
        </fieldset>
        <div className="field">
          <label htmlFor="beat-title">{c.title}</label>
          <input id="beat-title" className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={c.beatTitlePlaceholder} />
        </div>
      </div>
      {(["include", "exclude"] as const).map((list) => (
        <fieldset key={list} className="field">
          <legend>{list === "include" ? c.include : c.exclude}</legend>
          <p className="hint" style={{ marginBlockStart: 0 }}>
            {c.instrumentsHint}
          </p>
          <div className="check-chips">
            {INSTRUMENTS.map((name) => {
              const on = settings[list].includes(name);
              return (
                <label key={name} className="check-chip">
                  <input
                    type="checkbox"
                    checked={on}
                    disabled={!on && settings[list].length >= 8}
                    onChange={(e) => toggle(list, name, e.target.checked)}
                  />
                  <span>{c.instruments[name]}</span>
                </label>
              );
            })}
          </div>
          {fieldError(list) && <p className="error">{fieldError(list)}</p>}
        </fieldset>
      ))}
      <div className="field">
        <label htmlFor="beat-extra">{c.beatExtra}</label>
        <input
          id="beat-extra"
          className="input"
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder={c.beatExtraPlaceholder}
          aria-describedby="beat-extra-count"
          aria-invalid={promptCount > MAX_PROMPT}
        />
        <p id="beat-extra-count" className={promptCount > MAX_PROMPT ? "counter over" : "counter"}>
          {promptCount} / {MAX_PROMPT}
        </p>
      </div>
    </div>
  );
}
