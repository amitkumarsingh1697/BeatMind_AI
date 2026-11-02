"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import RequireAccount, { AccountBar } from "@/components/Account";
import AiLabel from "@/components/AiLabel";
import { MusicIcon, PencilIcon, SearchIcon, TrashIcon } from "@/components/Icons";
import { languageName, lengthLabel, problem, shortDate } from "@/lib/format";
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
  takes: { index: number; status: string; audioUrl: string | null }[];
};

const STATUS: Record<Song["status"], { text: string; tone: string }> = {
  queued: { text: "Waiting", tone: "badge" },
  running: { text: "Making", tone: "badge badge-accent" },
  succeeded: { text: "Ready", tone: "badge badge-success" },
  failed: { text: "Failed, refunded", tone: "badge badge-danger" },
};

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
  const searchSeq = useRef(0);

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
      setAnnounce(q.trim() ? `${n}${body.next ? "+" : ""} song${n === 1 ? "" : "s"} found for “${q.trim()}”.` : "");
    }
  }, []);

  useEffect(() => {
    const t = setTimeout(() => load(query), 250);
    return () => clearTimeout(t);
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
          <p className="eyebrow">Library</p>
          <h1>Your songs</h1>
        </div>
        <a className="btn btn-primary" href={`/${locale}/create`}>
          Make a song
        </a>
      </header>

      <form role="search" onSubmit={submit} className="field">
        <label htmlFor="library-search">Search your songs</label>
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
            placeholder="Title, lyrics or vibe, in Hindi or English (try “raat”)"
            style={{ paddingInlineStart: 42 }}
            aria-describedby="search-hint"
          />
        </div>
        <p id="search-hint" className="hint">
          Typing in English letters also finds Hindi titles, so “raat ka safar” finds “रात का सफ़र”.
        </p>
      </form>
      <p className="visually-hidden" role="status" aria-live="polite">
        {announce}
      </p>

      {songs === null ? (
        <div className="cluster muted" aria-busy="true">
          <span className="spinner" aria-hidden="true" /> Loading your songs…
        </div>
      ) : songs.length === 0 ? (
        <div className="empty">
          <MusicIcon size={32} />
          {query.trim() ? (
            <>
              <h2>No songs match “{query.trim()}”</h2>
              <p>Try fewer words, or search in the other script.</p>
            </>
          ) : (
            <>
              <h2>No songs yet</h2>
              <p>Describe a vibe or paste your lyrics, and your songs will be kept here.</p>
              <a className="btn btn-primary" href={`/${locale}/create`}>
                Make your first song
              </a>
            </>
          )}
        </div>
      ) : (
        <>
          <ul className="song-list" aria-label="Songs">
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
                    {languageName(s.languageTag)} · {s.mode === "lyrics" ? "Your lyrics" : "Vibe"} · {lengthLabel(s.durationSeconds)} ·{" "}
                    {shortDate(s.createdAt)}
                  </p>
                  <div className="cluster" style={{ marginBlockStart: "var(--space-2)" }}>
                    <AiLabel />
                    <span className={STATUS[s.status].tone}>{STATUS[s.status].text}</span>
                  </div>
                </div>
                <div className="actions">
                  <button type="button" className="btn btn-ghost btn-icon" onClick={() => setRenaming(s)} aria-label={`Rename ${s.title}`}>
                    <PencilIcon size={18} />
                  </button>
                  <button type="button" className="btn btn-ghost btn-icon" onClick={() => setDeleting(s)} aria-label={`Delete ${s.title}`}>
                    <TrashIcon size={18} />
                  </button>
                </div>
              </li>
            ))}
          </ul>
          {next && (
            <button type="button" className="btn btn-secondary" onClick={() => load(query, next)}>
              Show more songs
            </button>
          )}
        </>
      )}

      {renaming && (
        <RenameDialog
          song={renaming}
          onClose={(updated) => {
            setRenaming(null);
            if (updated) {
              setSongs((list) => list?.map((s) => (s.id === updated.id ? { ...s, title: updated.title } : s)) ?? null);
              setAnnounce(`Renamed to ${updated.title}.`);
            }
          }}
        />
      )}
      {deleting && (
        <DeleteDialog
          song={deleting}
          onClose={(deleted) => {
            setDeleting(null);
            if (deleted) {
              setSongs((list) => list?.filter((s) => s.id !== deleted.id) ?? null);
              setAnnounce(`${deleted.title} deleted.`);
            }
          }}
        />
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

export function RenameDialog({ song, onClose }: { song: { id: string; title: string }; onClose: (updated?: { id: string; title: string }) => void }) {
  const ref = useModal();
  const [title, setTitle] = useState(song.title);
  const [error, setError] = useState("");

  async function save(e: FormEvent) {
    e.preventDefault();
    const r = await fetch(`/v1/songs/${song.id}`, {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ title }),
    });
    if (!r.ok) return setError((await problem(r)).detail);
    const body = await r.json();
    onClose({ id: song.id, title: body.title });
  }

  return (
    <dialog ref={ref} aria-labelledby="rename-heading" onCancel={() => onClose()}>
      <h2 id="rename-heading">Rename song</h2>
      <form onSubmit={save}>
        <div className="field">
          <label htmlFor="rename-title">Title</label>
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
            Cancel
          </button>
          <button type="submit" className="btn btn-primary">
            Save
          </button>
        </div>
      </form>
    </dialog>
  );
}

export function DeleteDialog({ song, onClose }: { song: { id: string; title: string }; onClose: (deleted?: { id: string; title: string }) => void }) {
  const ref = useModal();
  const [error, setError] = useState("");

  async function remove() {
    const r = await fetch(`/v1/songs/${song.id}`, { method: "DELETE" });
    if (!r.ok) return setError((await problem(r)).detail);
    onClose(song);
  }

  return (
    <dialog ref={ref} aria-labelledby="delete-heading" aria-describedby="delete-text" onCancel={() => onClose()}>
      <h2 id="delete-heading">Delete “{song.title}”?</h2>
      <p id="delete-text" className="muted">
        It leaves your library now and its download links stop working. The record that it was AI-generated is kept, as the law
        requires.
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div className="cluster" style={{ justifyContent: "flex-end" }}>
        <button type="button" className="btn btn-ghost" onClick={() => onClose()} autoFocus>
          Keep it
        </button>
        <button type="button" className="btn btn-danger" onClick={remove}>
          <TrashIcon size={18} /> Delete song
        </button>
      </div>
    </dialog>
  );
}
