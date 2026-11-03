"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import RequireAccount from "@/components/Account";
import AiLabel from "@/components/AiLabel";
import { ArrowLeftIcon, DownloadIcon, MusicIcon, PencilIcon, ShieldIcon, TrashIcon } from "@/components/Icons";
import Player from "@/components/Player";
import { dateTime, languageName, lengthLabel, problem } from "@/lib/format";
import { appText, errorText, type AppText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import type { Me } from "@/lib/session";
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
  certificateId: string | null;
  createdAt: string;
};
type LedgerEvent = { seq: number; type: string; actor: "human" | "ai" | "system"; createdAt: string; hash: string };
type Provenance = { events: LedgerEvent[]; verification: { valid: boolean; events: number; reason: string | null } };
type Link = { url: string; expiresAt: string; quality: string; fileName: string; label: string; rights: string };
type Quota = { used: number; limit: number; month: string };

export default function SongPage({ locale, id }: { locale: Locale; id: string }) {
  return <RequireAccount locale={locale}>{(me) => <SongView me={me} locale={locale} id={id} />}</RequireAccount>;
}

function SongView({ me, locale, id }: { me: Me; locale: Locale; id: string }) {
  const [song, setSong] = useState<Song | null | undefined>(undefined);
  const [provenance, setProvenance] = useState<Provenance | null>(null);
  const [take, setTake] = useState(1);
  const [renaming, setRenaming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const t = appText(locale);
  const S = t.song;

  const load = useCallback(async () => {
    const r = await fetch(`/v1/songs/${id}`, { cache: "no-store" });
    if (!r.ok) return setSong(null);
    const s: Song = await r.json();
    setSong(s);
    const first = s.takes.find((tk) => tk.audioUrl);
    if (first) setTake((current) => (s.takes.some((tk) => tk.index === current && tk.audioUrl) ? current : first.index));
    fetch(`/v1/songs/${id}/provenance`, { cache: "no-store" })
      .then((p) => (p.ok ? p.json() : null))
      .then(setProvenance);
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (song && (song.status === "queued" || song.status === "running")) {
      const timer = setTimeout(load, 2000);
      return () => clearTimeout(timer);
    }
  }, [song, load]);

  if (song === undefined) {
    return (
      <div className="cluster muted" aria-busy="true">
        <span className="spinner" aria-hidden="true" /> {S.loading}
      </div>
    );
  }
  if (song === null) {
    return (
      <div className="empty">
        <h1>{S.notFound}</h1>
        <p>{S.notFoundHint}</p>
        <a className="btn btn-secondary" href={`/${locale}/library`}>
          {S.back}
        </a>
      </div>
    );
  }

  const current = song.takes.find((tk) => tk.index === take);
  const titleLang = song.languageTag === "hi" ? "hi" : undefined;

  return (
    <div className="stack-lg">
      <a href={`/${locale}/library`} className="cluster small">
        <ArrowLeftIcon size={16} /> {S.library}
      </a>

      <header className="cluster" style={{ gap: "var(--space-5)", alignItems: "flex-start" }}>
        <div className="cover lg" aria-hidden="true">
          <MusicIcon size={48} />
        </div>
        <div className="stack" style={{ flex: 1, minInlineSize: 240, gap: "var(--space-2)" }}>
          <p className="eyebrow" style={{ margin: 0 }}>
            {song.mode === "lyrics" ? S.fromLyrics : S.fromVibe}
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
            {languageName(song.languageTag, t.languageByTag)} · {lengthLabel(song.durationSeconds, t.time)}
            {song.vocalType ? S.voice(t.create.voices[song.vocalType] ?? song.vocalType) : song.instrumental ? S.instrumental : ""}
            {S.made(dateTime(song.createdAt, t.intlLocale))}
          </p>
          <div className="cluster">
            <AiLabel text={S.aiMusic} />
            <span className={song.rightsStatus === "bm_owned" ? "badge" : "badge badge-success"}>
              {song.rightsStatus === "user_owned" ? S.youOwn : song.rightsStatus === "free_licensed" ? S.licensed : S.personal}
            </span>
          </div>
        </div>
      </header>

      {song.status === "queued" || song.status === "running" ? (
        <div className="notice cluster" role="status">
          <span className="spinner" aria-hidden="true" /> {S.making}
        </div>
      ) : song.status === "failed" ? (
        <div className="notice notice-danger" role="status">
          <strong>{S.failed}</strong> {S.failedHint}
        </div>
      ) : (
        <section aria-labelledby="listen-heading" className="stack">
          <div className="spread">
            <h2 id="listen-heading" style={{ margin: 0 }}>
              {S.listen}
            </h2>
            <div className="take-switch" role="group" aria-label={S.chooseTake}>
              {song.takes.map((tk) => (
                <button
                  key={tk.index}
                  type="button"
                  className="btn btn-secondary btn-sm"
                  aria-pressed={tk.index === take}
                  disabled={!tk.audioUrl}
                  onClick={() => setTake(tk.index)}
                >
                  {S.take(tk.index)}
                  {tk.status === "withheld" && S.heldBackTag}
                </button>
              ))}
            </div>
          </div>
          {current?.audioUrl && (
            <Player src={current.audioUrl} title={song.title} takeLabel={S.take(current.index)} durationHint={song.durationSeconds} locale={locale} />
          )}
          {song.takes
            .filter((tk) => tk.status === "withheld")
            .map((tk) => (
              <TakeAppeal key={tk.index} songId={song.id} index={tk.index} t={t} locale={locale} />
            ))}
        </section>
      )}

      <div className="grid-2">
        <section className="card flat" aria-labelledby="lyrics-heading">
          <h2 id="lyrics-heading">{S.lyrics}</h2>
          {song.lyrics ? (
            <p className="lyrics-text" lang={song.languageTag}>
              {song.lyrics}
            </p>
          ) : song.instrumental ? (
            <p className="muted">{S.instrumentalText}</p>
          ) : (
            <p className="muted">{S.aiLyrics}</p>
          )}
          <h3 style={{ marginBlockStart: "var(--space-5)" }}>{song.mode === "lyrics" ? S.style : S.description}</h3>
          <p lang={song.languageTag} style={{ overflowWrap: "anywhere" }}>
            {song.prompt || S.noStyle}
          </p>
        </section>

        <div className="stack">
          {song.status === "succeeded" && current?.audioUrl && (
            <Downloads song={song} take={current.index} plan={me.plan} t={t} locale={locale} />
          )}
          <Rights song={song} t={t} locale={locale} onChange={load} />
        </div>
      </div>

      {provenance && <History provenance={provenance} t={t} />}

      <section className="card flat" aria-labelledby="manage-heading">
        <h2 id="manage-heading">{S.manage}</h2>
        <div className="cluster">
          <button type="button" className="btn btn-secondary" onClick={() => setRenaming(true)}>
            <PencilIcon size={18} /> {S.rename}
          </button>
          <button type="button" className="btn btn-danger" onClick={() => setDeleting(true)}>
            <TrashIcon size={18} /> {S.delete}
          </button>
        </div>
      </section>

      {renaming && (
        <RenameDialog
          locale={locale}
          song={song}
          onClose={(updated) => {
            setRenaming(false);
            if (updated) load();
          }}
        />
      )}
      {deleting && (
        <DeleteDialog
          locale={locale}
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

function Downloads({ song, take, plan, t, locale }: { song: Song; take: number; plan: Me["plan"]; t: AppText; locale: Locale }) {
  const S = t.song;
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
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    setLink(await r.json());
    dialog.current?.showModal();
  }

  return (
    <section className="card flat" aria-labelledby="downloads-heading">
      <h2 id="downloads-heading">{S.downloadHeading(take)}</h2>
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
          <DownloadIcon size={18} /> {S.wav}
        </button>
        <p id="wav-note" className="small muted" style={{ margin: 0 }}>
          {paid ? S.paidNote(t.plans[plan]) : S.freeNote}
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
            <h2 id="download-ready">{S.ready}</h2>
            <dl className="facts">
              <dt>{S.file}</dt>
              <dd>{link.fileName}</dd>
              <dt>{S.quality}</dt>
              <dd>{link.quality}</dd>
              <dt>{S.label}</dt>
              <dd>
                <AiLabel text={t.common.ai} />
              </dd>
              <dt>{S.rights}</dt>
              <dd>{t.rights[song.rightsStatus as "user_owned"] ?? link.rights}</dd>
            </dl>
            <p className="notice notice-ai small">
              <ShieldIcon size={16} /> {S.fileNotice}
            </p>
            <p className="small muted">{S.linkTime}</p>
            <div className="cluster" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn btn-ghost" onClick={() => dialog.current?.close()}>
                {t.common.close}
              </button>
              <a className="btn btn-primary" href={link.url} download={link.fileName} onClick={() => setTimeout(() => dialog.current?.close(), 300)}>
                <DownloadIcon size={18} /> {S.download}
              </a>
            </div>
          </div>
        )}
      </dialog>
    </section>
  );
}

function History({ provenance, t }: { provenance: Provenance; t: AppText }) {
  const { events, verification } = provenance;
  const S = t.song;
  return (
    <section className="card flat" aria-labelledby="history-heading">
      <div className="spread">
        <h2 id="history-heading" style={{ margin: 0 }}>
          {S.history}
        </h2>
        {verification.valid ? (
          <span className="badge badge-success">
            <ShieldIcon size={14} /> {S.verified}
          </span>
        ) : (
          <span className="badge badge-danger">{S.checkFailed}</span>
        )}
      </div>
      <p className="small muted">{S.historyIntro}</p>
      <ol className="history">
        {events.map((e) => (
          <li key={e.seq}>
            <span className={`dot ${e.actor}`} aria-hidden="true" />
            <span>
              {S.events[e.type] ?? e.type}{" "}
              <span className="small muted">
                · {S.actor[e.actor]} · {dateTime(e.createdAt, t.intlLocale)}
              </span>
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}

/** A withheld take (US-029): the owner can ask a person to review it (REQ-F-59). */
function TakeAppeal({ songId, index, t, locale }: { songId: string; index: number; t: AppText; locale: Locale }) {
  const [state, setState] = useState<"idle" | "sending" | "sent">("idle");
  const [error, setError] = useState("");

  async function appeal() {
    setState("sending");
    setError("");
    const r = await fetch(`/v1/songs/${songId}/takes/${index}/appeal`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({}),
    });
    if (r.ok || r.status === 409) return setState("sent");
    setError(errorText(locale, await problem(r)));
    setState("idle");
  }

  return (
    <div className="notice stack small">
      <p style={{ margin: 0 }}>{t.song.heldBack}</p>
      {state === "sent" ? (
        <p role="status" style={{ margin: 0 }}>
          {t.song.appealed}
        </p>
      ) : (
        <div>
          <button type="button" className="btn btn-secondary btn-sm" onClick={appeal} disabled={state === "sending"}>
            {state === "sending" ? t.common.sending : t.song.appealTake(index)}
          </button>
        </div>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

/**
 * Who owns the song and what the creator may do with it (US-025). Free plans can licence up to 5
 * songs a month for commercial use with attribution; paid plans own their songs and can get a
 * certificate that records it. Nothing here promises copyright protection (REQ-F-26).
 */
function Rights({ song, t, locale, onChange }: { song: Song; t: AppText; locale: Locale; onChange: () => void }) {
  const R = t.rights;
  const [quota, setQuota] = useState<Quota | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const free = song.rightsStatus === "bm_owned";
  const licensed = song.rightsStatus === "free_licensed";
  const owned = song.rightsStatus === "user_owned";
  const ready = song.status === "succeeded";

  useEffect(() => {
    if (!free) return;
    fetch("/v1/licences/quota", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then(setQuota)
      .catch(() => setQuota(null));
  }, [free]);

  async function issue() {
    setBusy(true);
    setError("");
    const r = await fetch(`/v1/songs/${song.id}/commercial-licence`, { method: "POST" });
    setBusy(false);
    dialog.current?.close();
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    onChange();
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard blocked: the text is on screen to copy by hand.
    }
  }

  const left = quota ? Math.max(0, quota.limit - quota.used) : null;
  const attribution = song.certificateId ? `Music: made with BeatMind AI (certificate ${song.certificateId})` : "Music: made with BeatMind AI";

  return (
    <section className="card flat stack" aria-labelledby="licence-heading">
      <h2 id="licence-heading" style={{ margin: 0 }}>
        {R.heading}
      </h2>
      <p style={{ fontSize: "var(--step-1)", fontWeight: 600, margin: 0 }}>{R[song.rightsStatus as "user_owned"] ?? song.rights}</p>
      <p className="small muted" style={{ margin: 0 }}>
        {R.fixed}
      </p>

      {free && ready && (
        <div className="stack">
          <h3 style={{ margin: 0 }}>{R.licenceHeading}</h3>
          <p className="small" style={{ margin: 0 }}>
            {R.licenceIntro}
          </p>
          {quota && left !== null && <p className="small muted" style={{ margin: 0 }}>{R.quota(left, quota.limit)}</p>}
          <div>
            <button type="button" className="btn btn-primary" disabled={busy || left === 0} onClick={() => dialog.current?.showModal()}>
              {R.licence}
            </button>
          </div>
        </div>
      )}

      {owned && ready && !song.certificateId && (
        <div className="stack">
          <p className="small" style={{ margin: 0 }}>
            {R.ownershipIntro}
          </p>
          <div>
            <button type="button" className="btn btn-secondary" disabled={busy} onClick={issue}>
              {R.getCertificate}
            </button>
          </div>
        </div>
      )}

      {song.certificateId && (
        <div className="stack">
          <a className="btn btn-secondary" href={`/${locale}/certificates/${song.certificateId}`}>
            <ShieldIcon size={18} /> {R.certificate}
          </a>
          {licensed && (
            <div className="field" style={{ margin: 0 }}>
              <label htmlFor="attribution">{R.attribution}</label>
              <div className="cluster" style={{ flexWrap: "nowrap" }}>
                <input id="attribution" className="input" readOnly value={attribution} onFocus={(e) => e.currentTarget.select()} />
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(attribution)}>
                  {copied ? t.common.copied : t.common.copy}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
      {(song.certificateId || free) && (
        <p className="small muted" style={{ margin: 0 }}>
          {R.notCopyright}
        </p>
      )}

      {error && (
        <p className="notice notice-danger small" role="alert">
          {error}
        </p>
      )}

      <dialog ref={dialog} aria-labelledby="licence-confirm">
        <h2 id="licence-confirm">{R.confirmHeading}</h2>
        <ul className="stack small">
          {R.confirmPoints.map((point) => (
            <li key={point}>{point}</li>
          ))}
        </ul>
        <div className="cluster" style={{ justifyContent: "flex-end" }}>
          <button type="button" className="btn btn-ghost" onClick={() => dialog.current?.close()} autoFocus>
            {t.common.cancel}
          </button>
          <button type="button" className="btn btn-primary" onClick={issue} disabled={busy}>
            {busy ? t.common.sending : R.confirm}
          </button>
        </div>
      </dialog>
    </section>
  );
}
