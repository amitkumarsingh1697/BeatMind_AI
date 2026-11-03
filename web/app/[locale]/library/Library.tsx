"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import RequireAccount, { AccountBar } from "@/components/Account";
import AiLabel from "@/components/AiLabel";
import { MusicIcon, PencilIcon, SearchIcon, TrashIcon } from "@/components/Icons";
import { languageName, lengthLabel, problem, shortDate } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import type { Me } from "@/lib/session";

export type Song = {
  id: string;
  title: string;
  titleLatin: string;
  languageTag: string;
  mode: "vibe" | "lyrics";
  prompt: string;
  status: "queued" | "running" | "succeeded" | "failed";
  durationSeconds: number;
  createdAt: string;
  rightsStatus?: string;
  takes: { index: number; status: string; audioUrl: string | null }[];
};

const TONE: Record<Song["status"], string> = {
  queued: "badge",
  running: "badge badge-accent",
  succeeded: "badge badge-success",
  failed: "badge badge-danger",
};

type Case = { id: string; kind: string; category: string | null; songId: string | null; takeIdx: number | null; status: string; decisionNote: string | null; createdAt: string };

export default function Library({ locale }: { locale: Locale }) {
  return <RequireAccount locale={locale}>{(me) => <SongLibrary me={me} locale={locale} />}</RequireAccount>;
}

function SongLibrary({ me, locale }: { me: Me; locale: Locale }) {
  const [query, setQuery] = useState("");
  const [songs, setSongs] = useState<Song[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [announce, setAnnounce] = useState("");
  const [renaming, setRenaming] = useState<Song | null>(null);
  const [deleting, setDeleting] = useState<Song | null>(null);
  const [cases, setCases] = useState<Case[]>([]);
  const searchSeq = useRef(0);
  const t = appText(locale);
  const L = t.library;

  useEffect(() => {
    fetch("/v1/moderation/cases", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : []))
      .then((list: Case[]) => setCases(list.filter((c) => c.status !== "open")))
      .catch(() => setCases([]));
  }, []);

  const found = L.found;
  const load = useCallback(async (q: string, before?: string) => {
    const seq = ++searchSeq.current;
    const params = new URLSearchParams();
    if (q.trim()) params.set("q", q.trim());
    if (before) params.set("before", before);
    const r = await fetch(`/v1/songs?${params}`, { cache: "no-store" });
    if (!r.ok || seq !== searchSeq.current) return;
    const body: { songs: Song[]; next: string | null } = await r.json();
    setSongs((list) => (before && list ? [...list, ...body.songs] : body.songs));
    setNext(body.next);
    if (!before) {
      const n = body.songs.length;
      setAnnounce(q.trim() ? found(n, body.next !== null, q.trim()) : "");
    }
  }, [found]);

  useEffect(() => {
    const timer = setTimeout(() => load(query), 250);
    return () => clearTimeout(timer);
  }, [query, load]);

  function submit(e: FormEvent) {
    e.preventDefault();
    load(query);
  }

  return (
    <div className="stack-lg">
      <AccountBar me={me} locale={locale} />
      <header className="page-head spread">
        <div>
          <p className="eyebrow">{L.eyebrow}</p>
          <h1>{L.heading}</h1>
        </div>
        <a className="btn btn-primary" href={`/${locale}/create`}>
          {L.make}
        </a>
      </header>

      <form role="search" onSubmit={submit} className="field">
        <label htmlFor="library-search">{L.search}</label>
        <div style={{ position: "relative" }}>
          <span style={{ position: "absolute", insetInlineStart: 12, insetBlockStart: 12, color: "var(--text-muted)" }}>
            <SearchIcon size={20} />
          </span>
          <input
            id="library-search"
            className="input"
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={L.searchPlaceholder}
            style={{ paddingInlineStart: 42 }}
            aria-describedby="search-hint"
          />
        </div>
        <p id="search-hint" className="hint">
          {L.searchHint}
        </p>
      </form>
      <p className="visually-hidden" role="status" aria-live="polite">
        {announce}
      </p>

      {songs === null ? (
        <div className="cluster muted" aria-busy="true">
          <span className="spinner" aria-hidden="true" /> {L.loading}
        </div>
      ) : songs.length === 0 ? (
        <div className="empty">
          <MusicIcon size={32} />
          {query.trim() ? (
            <>
              <h2>{L.noMatch(query.trim())}</h2>
              <p>{L.noMatchHint}</p>
            </>
          ) : (
            <>
              <h2>{L.none}</h2>
              <p>{L.noneHint}</p>
              <a className="btn btn-primary" href={`/${locale}/create`}>
                {L.first}
              </a>
            </>
          )}
        </div>
      ) : (
        <>
          <ul className="song-list" aria-label={L.songs}>
            {songs.map((s) => (
              <li key={s.id} className="song-card">
                <div className="cover" aria-hidden="true">
                  <MusicIcon size={24} />
                </div>
                <div style={{ minInlineSize: 0 }}>
                  <p className="title" lang={s.languageTag === "hi" ? "hi" : undefined}>
                    <a href={`/${locale}/songs/${s.id}`}>{s.title}</a>
                  </p>
                  <p className="meta">
                    {languageName(s.languageTag, t.languageByTag)} · {s.mode === "lyrics" ? L.yourLyrics : L.vibe} ·{" "}
                    {lengthLabel(s.durationSeconds, t.time)} · {shortDate(s.createdAt, t.intlLocale)}
                  </p>
                  <div className="cluster" style={{ marginBlockStart: "var(--space-2)" }}>
                    <AiLabel text={t.common.ai} />
                    <span className={TONE[s.status]}>{L.status[s.status]}</span>
                    {s.rightsStatus === "free_licensed" && <span className="badge badge-accent">{L.licensed}</span>}
                    {s.rightsStatus === "user_owned" && <span className="badge badge-success">{L.owned}</span>}
                  </div>
                </div>
                <div className="actions">
                  <button type="button" className="btn btn-ghost btn-icon" onClick={() => setRenaming(s)} aria-label={L.rename(s.title)}>
                    <PencilIcon size={18} />
                  </button>
                  <button type="button" className="btn btn-ghost btn-icon" onClick={() => setDeleting(s)} aria-label={L.delete(s.title)}>
                    <TrashIcon size={18} />
                  </button>
                </div>
              </li>
            ))}
          </ul>
          {next && (
            <button type="button" className="btn btn-secondary" onClick={() => load(query, next)}>
              {L.more}
            </button>
          )}
        </>
      )}

      {renaming && (
        <RenameDialog
          locale={locale}
          song={renaming}
          onClose={(updated) => {
            setRenaming(null);
            if (updated) {
              setSongs((list) => list?.map((s) => (s.id === updated.id ? { ...s, title: updated.title } : s)) ?? null);
              setAnnounce(L.renamed(updated.title));
            }
          }}
        />
      )}
      {deleting && (
        <DeleteDialog
          locale={locale}
          song={deleting}
          onClose={(deleted) => {
            setDeleting(null);
            if (deleted) {
              setSongs((list) => list?.filter((s) => s.id !== deleted.id) ?? null);
              setAnnounce(L.deleted(deleted.title));
            }
          }}
        />
      )}

      {cases.length > 0 && (
        <section aria-labelledby="reviews-heading" className="stack">
          <h2 id="reviews-heading">{L.reviews}</h2>
          <ul className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }}>
            {cases.map((c) => (
              <li key={c.id} className="card tight flat spread">
                <span>
                  <strong>{L.reviewKind[c.kind] ?? c.kind}</strong>
                  {c.songId && (
                    <>
                      {" · "}
                      <a href={`/${locale}/songs/${c.songId}`}>{t.song.take(c.takeIdx ?? 1)}</a>
                    </>
                  )}
                  <span className="small muted"> · {shortDate(c.createdAt, t.intlLocale)}</span>
                  {c.decisionNote && <span className="small muted"> · {c.decisionNote}</span>}
                </span>
                <span className={c.status === "overturned" ? "badge badge-success" : c.status === "upheld" ? "badge badge-danger" : "badge"}>
                  {L.reviewStatus[c.status] ?? c.status}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

/** Native dialog: focus moves in, Escape closes, focus returns to the button that opened it. */
function useModal() {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const el = ref.current;
    const opener = document.activeElement as HTMLElement | null;
    el?.showModal();
    return () => opener?.focus();
  }, []);
  return ref;
}

export function RenameDialog({
  locale,
  song,
  onClose,
}: {
  locale: Locale;
  song: { id: string; title: string };
  onClose: (updated?: { id: string; title: string }) => void;
}) {
  const ref = useModal();
  const t = appText(locale);
  const [title, setTitle] = useState(song.title);
  const [error, setError] = useState("");

  async function save(e: FormEvent) {
    e.preventDefault();
    const r = await fetch(`/v1/songs/${song.id}`, {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ title }),
    });
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    const body = await r.json();
    onClose({ id: song.id, title: body.title });
  }

  return (
    <dialog ref={ref} aria-labelledby="rename-heading" onCancel={() => onClose()}>
      <h2 id="rename-heading">{t.library.renameHeading}</h2>
      <form onSubmit={save}>
        <div className="field">
          <label htmlFor="rename-title">{t.library.titleField}</label>
          <input
            id="rename-title"
            className="input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            autoFocus
            aria-invalid={error !== ""}
            aria-describedby="rename-error"
          />
          {error && (
            <p id="rename-error" className="error" role="alert">
              {error}
            </p>
          )}
        </div>
        <div className="cluster" style={{ justifyContent: "flex-end" }}>
          <button type="button" className="btn btn-ghost" onClick={() => onClose()}>
            {t.common.cancel}
          </button>
          <button type="submit" className="btn btn-primary">
            {t.common.save}
          </button>
        </div>
      </form>
    </dialog>
  );
}

export function DeleteDialog({
  locale,
  song,
  onClose,
}: {
  locale: Locale;
  song: { id: string; title: string };
  onClose: (deleted?: { id: string; title: string }) => void;
}) {
  const ref = useModal();
  const L = appText(locale).library;
  const [error, setError] = useState("");

  async function remove() {
    const r = await fetch(`/v1/songs/${song.id}`, { method: "DELETE" });
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    onClose(song);
  }

  return (
    <dialog ref={ref} aria-labelledby="delete-heading" aria-describedby="delete-text" onCancel={() => onClose()}>
      <h2 id="delete-heading">{L.deleteHeading(song.title)}</h2>
      <p id="delete-text" className="muted">
        {L.deleteText}
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div className="cluster" style={{ justifyContent: "flex-end" }}>
        <button type="button" className="btn btn-ghost" onClick={() => onClose()} autoFocus>
          {L.keep}
        </button>
        <button type="button" className="btn btn-danger" onClick={remove}>
          <TrashIcon size={18} /> {L.deleteSong}
        </button>
      </div>
    </dialog>
  );
}
