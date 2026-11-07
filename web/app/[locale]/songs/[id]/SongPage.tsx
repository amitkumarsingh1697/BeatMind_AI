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
import Blocked from "@/components/Blocked";
import { DeleteDialog, RenameDialog } from "../../library/Library";
import EditPanel from "./EditPanel";
import StemsPanel from "./StemsPanel";

type Take = {
  index: number;
  status: "ready" | "withheld" | "failed";
  audioUrl: string | null;
  format: string | null;
  tempoBpm: number | null;
  /** The current version after edits (US-038); in the audio URL so the browser does not replay a cached file. */
  version: number;
  /** Vibe mode: the words this take sings. Each take is its own composition, so they differ. */
  lyrics?: string | null;
};
type Beat = { genre: string; bpm: number; key: string; include: string[]; exclude: string[] };
type Takedown = {
  id: string;
  rightClaimed: string;
  status: "actioned" | "appealed" | "restored";
  decisionNote: string | null;
  appealNote: string | null;
  appealDueAt: string | null;
  appealDecisionNote: string | null;
  appealDecidedAt: string | null;
};
type Share = { id: string; visibility: "private" | "public"; takeIndex: number; hint: string; views: number; createdAt: string; url: string | null };
type Song = {
  id: string;
  title: string;
  titleLatin: string;
  languageTag: string;
  mode: "vibe" | "lyrics" | "beat";
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
  beat: Beat | null;
  takedown: Takedown | null;
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
            {song.mode === "lyrics" ? S.fromLyrics : song.mode === "beat" ? S.fromBeat : S.fromVibe}
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
          {song.beat && <BeatFacts beat={song.beat} t={t} />}
          <div className="cluster">
            <AiLabel text={S.aiMusic} />
            {song.rightsStatus === "disabled" ? (
              <span className="badge badge-danger">{S.removedBadge}</span>
            ) : (
              <span className={song.rightsStatus === "bm_owned" || song.rightsStatus === "vesting" ? "badge" : "badge badge-success"}>
                {song.rightsStatus === "user_owned"
                  ? S.youOwn
                  : song.rightsStatus === "vesting"
                    ? S.pending
                    : song.rightsStatus === "free_licensed"
                      ? S.licensed
                      : S.personal}
              </span>
            )}
          </div>
        </div>
      </header>

      {song.takedown && <TakedownNotice song={song} takedown={song.takedown} t={t} locale={locale} onChange={load} />}

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
            <Player key={current.version} src={current.version > 1 ? `${current.audioUrl}?v=${current.version}` : current.audioUrl} title={song.title} takeLabel={S.take(current.index)} durationHint={song.durationSeconds} locale={locale} />
          )}
          {current?.tempoBpm != null && <p className="small muted" style={{ margin: 0 }}>{t.create.tempo(Math.round(current.tempoBpm))}</p>}
          {song.takes
            .filter((tk) => tk.status === "withheld")
            .map((tk) => (
              <TakeAppeal key={tk.index} songId={song.id} index={tk.index} t={t} locale={locale} />
            ))}
        </section>
      )}

      {song.status === "succeeded" && current?.audioUrl && current.status === "ready" && song.rightsStatus !== "disabled" && (
        <EditPanel
          key={current.index}
          songId={song.id}
          take={current.index}
          duration={song.durationSeconds}
          title={song.title}
          locale={locale}
          onChange={load}
        />
      )}
      {song.status === "succeeded" && current?.audioUrl && current.status === "ready" && song.rightsStatus !== "disabled" && (
        <StemsPanel
          key={`stems-${current.index}`}
          songId={song.id}
          take={current.index}
          version={current.version}
          allowed={me.plan !== "free" || song.rightsStatus === "user_owned"}
          locale={locale}
        />
      )}

      <div className="grid-2">
        <section className="card flat" aria-labelledby="lyrics-heading">
          <h2 id="lyrics-heading">{S.lyrics}</h2>
          {current?.lyrics || song.lyrics ? (
            <p className="lyrics-text" lang={song.languageTag}>
              {current?.lyrics || song.lyrics}
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
          {song.status === "succeeded" && current?.audioUrl && song.rightsStatus !== "disabled" && (
            <Downloads song={song} take={current.index} plan={me.plan} t={t} locale={locale} />
          )}
          <Rights song={song} t={t} locale={locale} onChange={load} />
          {song.status === "succeeded" && song.rightsStatus !== "disabled" && <SharePanel song={song} take={take} t={t} locale={locale} />}
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

function BeatFacts({ beat, t }: { beat: Beat; t: AppText }) {
  const c = t.create;
  const key = beat.key === "any" ? c.anyKey : beat.key.replace("major", c.major).replace("minor", c.minor);
  const names = (list: string[]) => list.map((i) => c.instruments[i] ?? i).join(", ");
  return (
    <p className="small" style={{ margin: 0 }}>
      {t.song.beatFacts(c.genres[beat.genre] ?? beat.genre, beat.bpm, key)}
      {beat.include.length > 0 && <> · {t.song.featuring(names(beat.include))}</>}
      {beat.exclude.length > 0 && <> · {t.song.without(names(beat.exclude))}</>}
    </p>
  );
}

/** US-032: why the song was removed, and the owner's appeal (REQ-F-59, 72 hours). */
function TakedownNotice({ song, takedown, t, locale, onChange }: { song: Song; takedown: Takedown; t: AppText; locale: Locale; onChange: () => void }) {
  const T = t.takedown;
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const right = T.rights[takedown.rightClaimed] ?? T.rights.other;

  async function appeal() {
    setBusy(true);
    setError("");
    const r = await fetch(`/v1/songs/${song.id}/takedown/appeal`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ note }),
    });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    onChange();
  }

  if (takedown.status === "restored") {
    return (
      <div className="notice notice-success" role="status">
        {T.restored}
      </div>
    );
  }
  const upheld = takedown.status === "actioned" && takedown.appealDecidedAt != null;
  return (
    <section className="notice notice-danger stack" aria-labelledby="takedown-heading">
      <h2 id="takedown-heading" style={{ margin: 0, fontSize: "var(--step-1)" }}>
        {T.heading}
      </h2>
      <p style={{ margin: 0 }}>{T.why(right)}</p>
      {takedown.decisionNote && (
        <p className="small" style={{ margin: 0 }}>
          <strong>{T.note}:</strong> {takedown.decisionNote}
        </p>
      )}
      {takedown.status === "appealed" && takedown.appealDueAt && (
        <p role="status" style={{ margin: 0 }}>
          {T.appealed(dateTime(takedown.appealDueAt, t.intlLocale))}
        </p>
      )}
      {upheld && (
        <p role="status" style={{ margin: 0 }}>
          {T.upheld} {takedown.appealDecisionNote}
        </p>
      )}
      {takedown.status === "actioned" && !upheld && (
        <div className="stack">
          <p className="small" style={{ margin: 0 }}>
            {T.appealIntro}
          </p>
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor="takedown-appeal">{T.appealLabel}</label>
            <textarea id="takedown-appeal" className="textarea" rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <div>
            <button type="button" className="btn btn-secondary" disabled={busy || note.trim() === ""} onClick={appeal}>
              {busy ? t.common.sending : T.appealSend}
            </button>
          </div>
        </div>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}

/**
 * US-024: share links. The full link is shown once, when it is made (only its hash is stored);
 * the list shows the last characters, plays and a way to stop each link.
 */
function SharePanel({ song, take, t, locale }: { song: Song; take: number; t: AppText; locale: Locale }) {
  const H = t.share;
  const [links, setLinks] = useState<Share[]>([]);
  const [visibility, setVisibility] = useState<"private" | "public">("private");
  const [made, setMade] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState<React.ReactNode>(null);
  const [copied, setCopied] = useState(false);

  const load = useCallback(() => {
    fetch(`/v1/songs/${song.id}/shares`, { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : []))
      .then(setLinks)
      .catch(() => setLinks([]));
  }, [song.id]);

  useEffect(() => {
    load();
  }, [load]);

  async function create() {
    setBusy(true);
    setError(null);
    setMessage("");
    const r = await fetch(`/v1/songs/${song.id}/shares`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ visibility, takeIndex: take, locale }),
    });
    setBusy(false);
    if (!r.ok) {
      const p = await problem(r);
      return setError(p.code?.startsWith("moderation_") ? <Blocked locale={locale} p={p} /> : <p className="error">{errorText(locale, p)}</p>);
    }
    const share: Share = await r.json();
    setMade(`${window.location.origin}${share.url}`);
    load();
  }

  async function revoke(id: string) {
    const r = await fetch(`/v1/songs/${song.id}/shares/${id}`, { method: "DELETE" });
    if (r.ok) {
      setMessage(H.revoked);
      setMade(null);
      load();
    }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard blocked: the link is selected on screen to copy by hand.
    }
  }

  return (
    <section className="card flat stack" aria-labelledby="share-heading">
      <h2 id="share-heading" style={{ margin: 0 }}>
        {H.heading}
      </h2>
      <p className="small muted" style={{ margin: 0 }}>
        {H.intro}
      </p>
      <fieldset className="field">
        <legend>{H.visibility}</legend>
        {(["private", "public"] as const).map((v) => (
          <label key={v} className="check">
            <input type="radio" name="share-visibility" value={v} checked={visibility === v} onChange={() => setVisibility(v)} />
            <span>{H[v]}</span>
          </label>
        ))}
      </fieldset>
      <div>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={create}>
          {busy ? H.making : `${H.make} · ${t.song.take(take)}`}
        </button>
      </div>
      <div aria-live="polite" className="stack">
        {made && (
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor="share-url">{H.made}</label>
            <div className="cluster" style={{ flexWrap: "nowrap" }}>
              <input id="share-url" className="input" readOnly value={made} onFocus={(e) => e.currentTarget.select()} />
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(made)}>
                {copied ? t.common.copied : t.common.copy}
              </button>
            </div>
          </div>
        )}
        {message && <p className="small" style={{ margin: 0 }}>{message}</p>}
        {error}
      </div>
      <h3 style={{ margin: 0 }}>{H.live}</h3>
      {links.length === 0 ? (
        <p className="small muted" style={{ margin: 0 }}>
          {H.none}
        </p>
      ) : (
        <ul className="share-list">
          {links.map((l) => (
            <li key={l.id} className="spread">
              <span className="small">
                <span className="badge">{H.kind[l.visibility]}</span> {H.linkHint(l.hint)} · {t.song.take(l.takeIndex)} · {H.views(l.views)}
              </span>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => revoke(l.id)} aria-label={`${H.revoke}: ${H.linkHint(l.hint)}`}>
                {H.revoke}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
