"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import RequireAccount from "@/components/Account";
import AiLabel from "@/components/AiLabel";
import { ArrowLeftIcon, DownloadIcon, MusicIcon, PencilIcon, ShieldIcon, TrashIcon } from "@/components/Icons";
import Player from "@/components/Player";
import { dateTime, languageName, lengthLabel, problem } from "@/lib/format";
import type { Locale } from "@/lib/messages";
import { PLAN_NAMES, type Me } from "@/lib/session";
import { DeleteDialog, RenameDialog } from "../../library/Library";

type Take = { index: number; status: "ready" | "withheld" | "failed"; audioUrl: string | null; format: string | null };
type Song = {
  id: string;
  title: string;
  titleLatin: string;
  languageTag: string;
  mode: "vibe" | "lyrics";
  prompt: string;
  lyrics: string | null;
  vocalType: string | null;
  instrumental: boolean;
  durationSeconds: number;
  status: "queued" | "running" | "succeeded" | "failed";
  rightsStatus: string;
  rights: string;
  label: string;
  takes: Take[];
  downloadFormats: string[];
  createdAt: string;
};
type LedgerEvent = { seq: number; type: string; actor: "human" | "ai" | "system"; createdAt: string; hash: string };
type Provenance = { events: LedgerEvent[]; verification: { valid: boolean; events: number; reason: string | null } };
type Link = { url: string; expiresAt: string; quality: string; fileName: string; label: string; rights: string };

const EVENT_TEXT: Record<string, string> = {
  prompt_submitted: "You described the song",
  lyrics_written: "You wrote the lyrics",
  credits_debited: "2 credits used",
  take_generated: "AI made a take",
  take_withheld: "A take was held back by the safety check",
  generation_failed: "Making the song failed",
  credits_refunded: "Credits refunded",
  exported: "You downloaded a file",
  renamed: "You renamed the song",
  deleted: "You deleted the song",
};

export default function SongPage({ locale, id }: { locale: Locale; id: string }) {
  return <RequireAccount locale={locale}>{(me) => <SongView me={me} locale={locale} id={id} />}</RequireAccount>;
}

function SongView({ me, locale, id }: { me: Me; locale: Locale; id: string }) {
  const [song, setSong] = useState<Song | null | undefined>(undefined);
  const [provenance, setProvenance] = useState<Provenance | null>(null);
  const [take, setTake] = useState(1);
  const [renaming, setRenaming] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    const r = await fetch(`/v1/songs/${id}`, { cache: "no-store" });
    if (!r.ok) return setSong(null);
    const s: Song = await r.json();
    setSong(s);
    const first = s.takes.find((t) => t.audioUrl);
    if (first) setTake((current) => (s.takes.some((t) => t.index === current && t.audioUrl) ? current : first.index));
    fetch(`/v1/songs/${id}/provenance`, { cache: "no-store" })
      .then((p) => (p.ok ? p.json() : null))
      .then(setProvenance);
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (song && (song.status === "queued" || song.status === "running")) {
      const t = setTimeout(load, 2000);
      return () => clearTimeout(t);
    }
  }, [song, load]);

  if (song === undefined) {
    return (
      <div className="cluster muted" aria-busy="true">
        <span className="spinner" aria-hidden="true" /> Loading song…
      </div>
    );
  }
  if (song === null) {
    return (
      <div className="empty">
        <h1>Song not found</h1>
        <p>It may have been deleted.</p>
        <a className="btn btn-secondary" href={`/${locale}/library`}>
          Back to your library
        </a>
      </div>
    );
  }

  const current = song.takes.find((t) => t.index === take);
  const titleLang = song.languageTag === "hi" ? "hi" : undefined;

  return (
    <div className="stack-lg">
      <a href={`/${locale}/library`} className="cluster small">
        <ArrowLeftIcon size={16} /> Library
      </a>

      <header className="cluster" style={{ gap: "var(--space-5)", alignItems: "flex-start" }}>
        <div className="cover lg" aria-hidden="true">
          <MusicIcon size={48} />
        </div>
        <div className="stack" style={{ flex: 1, minInlineSize: 240, gap: "var(--space-2)" }}>
          <p className="eyebrow" style={{ margin: 0 }}>
            {song.mode === "lyrics" ? "Song from your lyrics" : "Song from a vibe"}
          </p>
          <h1 lang={titleLang} style={{ margin: 0, overflowWrap: "anywhere" }}>
            {song.title}
          </h1>
          {titleLang && song.titleLatin && (
            <p className="muted" lang="hi-Latn" style={{ margin: 0 }}>
              {song.titleLatin}
            </p>
          )}
          <p className="muted small" style={{ margin: 0 }}>
            {languageName(song.languageTag)} · {lengthLabel(song.durationSeconds)}
            {song.vocalType ? ` · ${song.vocalType} voice` : song.instrumental ? " · instrumental" : ""} · made {dateTime(song.createdAt)}
          </p>
          <div className="cluster">
            <AiLabel text={`${song.label} music`} />
            <span className="badge">{song.rightsStatus === "user_owned" ? "You own this song" : "Personal listening"}</span>
          </div>
        </div>
      </header>

      {song.status === "queued" || song.status === "running" ? (
        <div className="notice cluster" role="status">
          <span className="spinner" aria-hidden="true" /> Making your two takes… this page updates on its own.
        </div>
      ) : song.status === "failed" ? (
        <div className="notice notice-danger" role="status">
          <strong>We could not make this song.</strong> Your credits were refunded automatically. Try again from the Create page.
        </div>
      ) : (
        <section aria-labelledby="listen-heading" className="stack">
          <div className="spread">
            <h2 id="listen-heading" style={{ margin: 0 }}>
              Listen
            </h2>
            <div className="take-switch" role="group" aria-label="Choose a take">
              {song.takes.map((t) => (
                <button
                  key={t.index}
                  type="button"
                  className="btn btn-secondary btn-sm"
                  aria-pressed={t.index === take}
                  disabled={!t.audioUrl}
                  onClick={() => setTake(t.index)}
                >
                  Take {t.index}
                  {t.status === "withheld" && " · held back"}
                </button>
              ))}
            </div>
          </div>
          {current?.audioUrl && <Player src={current.audioUrl} title={song.title} takeLabel={`Take ${current.index}`} durationHint={song.durationSeconds} />}
          {song.takes.some((t) => t.status === "withheld") && (
            <p className="small muted">A take held back by our safety check is not playable. Its credit was refunded.</p>
          )}
        </section>
      )}

      <div className="grid-2">
        <section className="card flat" aria-labelledby="lyrics-heading">
          <h2 id="lyrics-heading">Lyrics</h2>
          {song.lyrics ? (
            <p className="lyrics-text" lang={song.languageTag}>
              {song.lyrics}
            </p>
          ) : song.instrumental ? (
            <p className="muted">Instrumental, no vocals.</p>
          ) : (
            <p className="muted">
              The AI wrote the words for this song from your description. Showing AI-written lyrics here comes in a later update.
            </p>
          )}
          <h3 style={{ marginBlockStart: "var(--space-5)" }}>{song.mode === "lyrics" ? "Style" : "Your description"}</h3>
          <p lang={song.languageTag} style={{ overflowWrap: "anywhere" }}>
            {song.prompt || "No style given."}
          </p>
        </section>

        <div className="stack">
          {song.status === "succeeded" && current?.audioUrl && (
            <Downloads song={song} take={current.index} plan={me.plan} />
          )}
          <section className="card flat" aria-labelledby="licence-heading">
            <h2 id="licence-heading">Who owns it</h2>
            <p style={{ fontSize: "var(--step-1)", fontWeight: 600 }}>{song.rights}</p>
            <p className="small muted">
              Rights are fixed by your plan when the song was made. Free licences for commercial use and certificates arrive with
              the licence panel in a later sprint.
            </p>
          </section>
        </div>
      </div>

      {provenance && <History provenance={provenance} />}

      <section className="card flat" aria-labelledby="manage-heading">
        <h2 id="manage-heading">Manage</h2>
        <div className="cluster">
          <button type="button" className="btn btn-secondary" onClick={() => setRenaming(true)}>
            <PencilIcon size={18} /> Rename
          </button>
          <button type="button" className="btn btn-danger" onClick={() => setDeleting(true)}>
            <TrashIcon size={18} /> Delete
          </button>
        </div>
      </section>

      {renaming && (
        <RenameDialog
          song={song}
          onClose={(updated) => {
            setRenaming(false);
            if (updated) load();
          }}
        />
      )}
      {deleting && (
        <DeleteDialog
          song={song}
          onClose={(deleted) => {
            setDeleting(false);
            if (deleted) window.location.href = `/${locale}/library`;
          }}
        />
      )}
    </div>
  );
}

function Downloads({ song, take, plan }: { song: Song; take: number; plan: Me["plan"] }) {
  const [link, setLink] = useState<Link | null>(null);
  const [error, setError] = useState("");
  const dialog = useRef<HTMLDialogElement>(null);
  const paid = plan !== "free";

  async function prepare(format: "mp3" | "wav") {
    setError("");
    const r = await fetch(`/v1/songs/${song.id}/takes/${take}/downloads`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ format }),
    });
    if (!r.ok) return setError((await problem(r)).detail);
    setLink(await r.json());
    dialog.current?.showModal();
  }

  return (
    <section className="card flat" aria-labelledby="downloads-heading">
      <h2 id="downloads-heading">Download take {take}</h2>
      <div className="stack" style={{ gap: "var(--space-2)" }}>
        <button type="button" className="btn btn-primary" onClick={() => prepare("mp3")}>
          <DownloadIcon size={18} /> MP3 {paid ? "· 320 kbps" : "· 192 kbps"}
        </button>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={() => prepare("wav")}
          disabled={!song.downloadFormats.includes("wav")}
          aria-describedby="wav-note"
        >
          <DownloadIcon size={18} /> WAV · 48 kHz, 24-bit
        </button>
        <p id="wav-note" className="small muted" style={{ margin: 0 }}>
          {paid ? `${PLAN_NAMES[plan]} plan: MP3 320 kbps and WAV.` : "Free plan: MP3 for personal listening. WAV comes with Creator and Pro."}
        </p>
      </div>
      {error && (
        <p className="notice notice-danger small" role="alert" style={{ marginBlockStart: "var(--space-3)" }}>
          {error}
        </p>
      )}
      <dialog ref={dialog} aria-labelledby="download-ready" onClose={() => setLink(null)}>
        {link && (
          <div className="stack">
            <h2 id="download-ready">Your file is ready</h2>
            <dl className="facts">
              <dt>File</dt>
              <dd>{link.fileName}</dd>
              <dt>Quality</dt>
              <dd>{link.quality}</dd>
              <dt>Label</dt>
              <dd>
                <AiLabel text={link.label} />
              </dd>
              <dt>Rights</dt>
              <dd>{link.rights}</dd>
            </dl>
            <p className="notice notice-ai small">
              <ShieldIcon size={16} /> The file says it is AI-generated in its tags, carries signed content credentials (C2PA) and an
              inaudible watermark. Please keep the label when you share it.
            </p>
            <p className="small muted">This link works for 15 minutes.</p>
            <div className="cluster" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn btn-ghost" onClick={() => dialog.current?.close()}>
                Close
              </button>
              <a className="btn btn-primary" href={link.url} download={link.fileName} onClick={() => setTimeout(() => dialog.current?.close(), 300)}>
                <DownloadIcon size={18} /> Download
              </a>
            </div>
          </div>
        )}
      </dialog>
    </section>
  );
}

function History({ provenance }: { provenance: Provenance }) {
  const { events, verification } = provenance;
  return (
    <section className="card flat" aria-labelledby="history-heading">
      <div className="spread">
        <h2 id="history-heading" style={{ margin: 0 }}>
          History
        </h2>
        {verification.valid ? (
          <span className="badge badge-success">
            <ShieldIcon size={14} /> Record verified
          </span>
        ) : (
          <span className="badge badge-danger">Record check failed</span>
        )}
      </div>
      <p className="small muted">
        Every step is written to a tamper-evident record that shows what you did and what the AI did.
      </p>
      <ol className="history">
        {events.map((e) => (
          <li key={e.seq}>
            <span className={`dot ${e.actor}`} aria-hidden="true" />
            <span>
              {EVENT_TEXT[e.type] ?? e.type}{" "}
              <span className="small muted">
                · {e.actor === "human" ? "you" : e.actor === "ai" ? "AI" : "BeatMind"} · {dateTime(e.createdAt)}
              </span>
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}
