"use client";

import { useCallback, useEffect, useState } from "react";
import { DownloadIcon } from "@/components/Icons";
import { problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";

type StemsView = {
  status: "processing" | "ready" | "failed";
  version: number;
  current: boolean;
  stems: { name: string; bytes: number }[];
  sumCheck: { passed: boolean; snrDb: number } | null;
  model: string | null;
};

const NAMES = ["vocals", "drums", "bass", "other"];

/**
 * Stems (US-039, REQ-F-23): separate the current version of a take into vocals, drums, bass and
 * everything else, then download each as a labelled, watermarked WAV through a signed link.
 */
export default function StemsPanel({
  songId,
  take,
  version,
  allowed,
  locale,
}: {
  songId: string;
  take: number;
  version: number;
  allowed: boolean;
  locale: Locale;
}) {
  const t = appText(locale);
  const S = t.stems;
  const [view, setView] = useState<StemsView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    const r = await fetch(`/v1/songs/${songId}/takes/${take}/stems`, { cache: "no-store" });
    setView(r.ok ? await r.json() : null);
  }, [songId, take]);

  useEffect(() => {
    if (allowed) load();
  }, [allowed, load, version]);

  useEffect(() => {
    if (view?.status !== "processing") return;
    const timer = setTimeout(load, 2000);
    return () => clearTimeout(timer);
  }, [view, load]);

  async function separate() {
    setBusy(true);
    setError("");
    const r = await fetch(`/v1/songs/${songId}/takes/${take}/stems`, { method: "POST" });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    setView(await r.json());
  }

  async function download(name: string) {
    setError("");
    const r = await fetch(`/v1/songs/${songId}/takes/${take}/stems/${name}/downloads`, { method: "POST" });
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    const link: { url: string } = await r.json();
    window.location.href = link.url;
  }

  return (
    <section className="card flat stack" aria-labelledby="stems-heading">
      <h2 id="stems-heading" style={{ margin: 0 }}>
        {S.heading}
      </h2>
      <p className="small muted" style={{ margin: 0 }}>
        {S.intro}
      </p>
      {!allowed ? (
        <p style={{ margin: 0 }}>
          {S.paidOnly}{" "}
          <a href={`/${locale}/pricing`}>{S.seePlans}</a>
        </p>
      ) : (
        <div aria-live="polite" className="stack">
          {view?.status === "processing" && (
            <p className="cluster" role="status" style={{ margin: 0 }}>
              <span className="spinner" aria-hidden="true" /> {S.working}
            </p>
          )}
          {view?.status === "failed" && (
            <p className="error" role="alert" style={{ margin: 0 }}>
              {S.failed}
            </p>
          )}
          {view?.status === "ready" && (
            <>
              {view.version !== version && (
                <p className="small" style={{ margin: 0 }}>
                  {S.older(view.version)}
                </p>
              )}
              <ul className="cluster" style={{ listStyle: "none", padding: 0, margin: 0 }}>
                {NAMES.map((name) => (
                  <li key={name}>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => download(name)}>
                      <DownloadIcon size={16} /> {S.download(S.names[name])}
                    </button>
                  </li>
                ))}
              </ul>
              {view.sumCheck && (
                <p className={view.sumCheck.passed ? "small muted" : "small error"} style={{ margin: 0 }}>
                  {view.sumCheck.passed ? S.sumOk : S.sumWarn}
                </p>
              )}
            </>
          )}
          {(!view || view.status === "failed" || (view.status === "ready" && view.version !== version)) && (
            <div>
              <button type="button" className="btn btn-primary" onClick={separate} disabled={busy}>
                {view?.status === "failed" ? S.again : S.separate}
              </button>
            </div>
          )}
          {error && (
            <p className="error" role="alert" style={{ margin: 0 }}>
              {error}
            </p>
          )}
        </div>
      )}
    </section>
  );
}
