import type { Metadata } from "next";
import { notFound } from "next/navigation";
import AiLabel from "@/components/AiLabel";
import AppShell from "@/components/AppShell";
import { MusicIcon, ShieldIcon } from "@/components/Icons";
import Player from "@/components/Player";
import { dateTime, languageName, lengthLabel } from "@/lib/format";
import { appText } from "@/lib/i18n";
import { isLocale } from "@/lib/messages";

export const dynamic = "force-dynamic";

type Shared = {
  title: string;
  languageTag: string;
  mode: "vibe" | "lyrics" | "beat";
  lyrics: string | null;
  instrumental: boolean;
  durationSeconds: number;
  visibility: "private" | "public";
  label: string;
  audioUrl: string;
  certificateId: string | null;
  createdAt: string;
};

type Loaded = { song: Shared } | { gone: "revoked" | "disabled" } | { missing: true };

const TOKEN = /^[A-Za-z0-9_-]{20,64}$/;

async function load(token: string): Promise<Loaded> {
  if (!TOKEN.test(token)) return { missing: true };
  const origin = process.env.API_ORIGIN ?? "http://localhost:8080";
  try {
    const r = await fetch(`${origin}/v1/shared/${token}`, { cache: "no-store" });
    if (r.ok) return { song: await r.json() };
    if (r.status === 410) {
      const body = await r.json().catch(() => ({}));
      return { gone: body.code === "disabled" ? "disabled" : "revoked" };
    }
  } catch {
    // API down: show the not-found state rather than an error page.
  }
  return { missing: true };
}

// A private link is never indexed; a public one may be (US-024, FEAT-13).
export async function generateMetadata({ params }: { params: Promise<{ locale: string; token: string }> }): Promise<Metadata> {
  const { token } = await params;
  const loaded = await load(token);
  if ("song" in loaded) {
    return {
      title: `${loaded.song.title} · ${loaded.song.label} · BeatMind AI`,
      robots: loaded.song.visibility === "public" ? undefined : { index: false, follow: false },
      referrer: "no-referrer",
    };
  }
  return { title: "BeatMind AI", robots: { index: false } };
}

/**
 * The public song page behind a share link (US-024): the take, its AI label, lyrics, and a way to
 * report it (US-032). No owner details. A revoked link or a removed song says so (410).
 */
export default async function SharedSongPage({ params }: { params: Promise<{ locale: string; token: string }> }) {
  const { locale, token } = await params;
  if (!isLocale(locale)) notFound();
  const t = appText(locale);
  const P = t.shared;
  const loaded = await load(token);
  const reportHref = `/${locale}/report?target=${encodeURIComponent(`/${locale}/s/${token}`)}`;

  if (!("song" in loaded)) {
    const text = "gone" in loaded ? (loaded.gone === "disabled" ? P.removed : P.gone) : P.missing;
    return (
      <AppShell locale={locale} variant="marketing">
        <div className="empty">
          <MusicIcon size={32} />
          <h1>{text}</h1>
          <a className="btn btn-primary" href={`/${locale}/create`}>
            {P.make}
          </a>
        </div>
      </AppShell>
    );
  }

  const song = loaded.song;
  const titleLang = song.languageTag === "hi" ? "hi" : undefined;
  return (
    <AppShell locale={locale} variant="marketing">
      <article className="stack-lg">
        <header className="cluster" style={{ gap: "var(--space-5)", alignItems: "flex-start" }}>
          <div className="cover lg" aria-hidden="true">
            <MusicIcon size={48} />
          </div>
          <div className="stack" style={{ flex: 1, minInlineSize: 240, gap: "var(--space-2)" }}>
            <p className="eyebrow" style={{ margin: 0 }}>
              {P.eyebrow}
            </p>
            <h1 lang={titleLang} style={{ margin: 0, overflowWrap: "anywhere" }}>
              {song.title}
            </h1>
            <p className="muted small" style={{ margin: 0 }}>
              {song.mode === "beat" ? t.song.fromBeat : languageName(song.languageTag, t.languageByTag)} · {lengthLabel(song.durationSeconds, t.time)}
              {t.song.made(dateTime(song.createdAt, t.intlLocale))}
            </p>
            <div className="cluster">
              <AiLabel text={t.song.aiMusic} />
              <span className="small muted">{P.madeWith}</span>
            </div>
          </div>
        </header>

        <Player src={song.audioUrl} title={song.title} takeLabel={t.common.ai} durationHint={song.durationSeconds} locale={locale} />
        <p className="notice notice-ai small">
          <ShieldIcon size={16} /> {P.aiNote}
        </p>

        {song.lyrics && (
          <section className="card flat" aria-labelledby="shared-lyrics">
            <h2 id="shared-lyrics">{P.lyrics}</h2>
            <p className="lyrics-text" lang={song.languageTag}>
              {song.lyrics}
            </p>
          </section>
        )}

        <div className="cluster">
          {song.certificateId && (
            <a className="btn btn-secondary" href={`/${locale}/certificates/${song.certificateId}`}>
              <ShieldIcon size={18} /> {P.certificate}
            </a>
          )}
          <a className="btn btn-primary" href={`/${locale}/create`}>
            {P.make}
          </a>
          <a href={reportHref} className="small" style={{ marginInlineStart: "auto" }}>
            {P.report}
          </a>
        </div>
      </article>
    </AppShell>
  );
}
