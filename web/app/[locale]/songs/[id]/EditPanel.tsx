"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import Player from "@/components/Player";
import { clock, problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";

type Version = {
  version: number;
  parent: number | null;
  op: "original" | "trim" | "fade" | "extend" | "regenerate";
  params: Record<string, number>;
  status: "processing" | "ready" | "failed";
  durationSeconds: number | null;
  current: boolean;
  audioUrl: string | null;
  seam: { passed: boolean; worstRatio: number } | null;
  createdAt: string;
};

const OPS = ["trim", "fade", "extend", "regenerate"] as const;
type Op = (typeof OPS)[number];

/**
 * The song editor (US-038, REQ-F-10, REQ-F-11). Trim and fade are free; extending or remaking a
 * section uses one credit and is refunded if it fails. Every edit is a new version, and any ready
 * version can be made current again.
 */
export default function EditPanel({
  songId,
  take,
  duration,
  title,
  locale,
  onChange,
}: {
  songId: string;
  take: number;
  duration: number;
  title: string;
  locale: Locale;
  onChange: () => void;
}) {
  const t = appText(locale);
  const E = t.editor;
  const [versions, setVersions] = useState<Version[]>([]);
  const [op, setOp] = useState<Op>("trim");
  const [start, setStart] = useState("0");
  const [end, setEnd] = useState(String(Math.floor(duration)));
  const [fadeIn, setFadeIn] = useState("2");
  const [fadeOut, setFadeOut] = useState("4");
  const [seconds, setSeconds] = useState("15");
  const [prompt, setPrompt] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [playing, setPlaying] = useState<number | null>(null);

  const load = useCallback(async () => {
    const r = await fetch(`/v1/songs/${songId}/takes/${take}/versions`, { cache: "no-store" });
    const list: Version[] = r.ok ? await r.json() : [];
    setVersions(list);
    return list;
  }, [songId, take]);

  useEffect(() => {
    load();
  }, [load]);

  const processing = versions.some((v) => v.status === "processing");
  const currentLength = versions.find((v) => v.current)?.durationSeconds ?? duration;

  useEffect(() => {
    setEnd(String(Math.floor(currentLength)));
    // Default fades that fit a short take: at most a third of it each, and 2 and 4 seconds.
    const third = Math.floor((currentLength / 3) * 2) / 2;
    setFadeIn(String(Math.min(2, third)));
    setFadeOut(String(Math.min(4, third)));
  }, [currentLength]);

  useEffect(() => {
    if (!processing) return;
    const timer = setTimeout(async () => {
      const list = await load();
      if (!list.some((v) => v.status === "processing")) onChange();
    }, 2000);
    return () => clearTimeout(timer);
  }, [processing, versions, load, onChange]);

  function params(): Record<string, number> {
    if (op === "trim" || op === "regenerate") return { start: Number(start), end: Number(end) };
    if (op === "fade") return { fadeIn: Number(fadeIn), fadeOut: Number(fadeOut) };
    return { seconds: Number(seconds) };
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const r = await fetch(`/v1/songs/${songId}/edits`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ take, op, params: params(), prompt: op === "regenerate" || op === "extend" ? prompt || undefined : undefined }),
    });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    await load();
  }

  async function restore(version: number) {
    setError("");
    const r = await fetch(`/v1/songs/${songId}/takes/${take}/versions/${version}/restore`, { method: "POST" });
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    await load();
    onChange();
  }

  const num = (id: string, label: string, value: string, set: (v: string) => void, max: number) => (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} className="input" type="number" inputMode="decimal" min={0} max={max} step={0.5} value={value} onChange={(e) => set(e.target.value)} />
    </div>
  );

  return (
    <section className="card flat stack" aria-labelledby="edit-heading">
      <h2 id="edit-heading" style={{ margin: 0 }}>
        {E.heading}
      </h2>
      <p className="small muted" style={{ margin: 0 }}>
        {E.intro}
      </p>
      <form className="stack" onSubmit={submit}>
        <fieldset className="segmented">
          <legend className="field-label">{E.op}</legend>
          {OPS.map((o) => (
            <label key={o}>
              <input type="radio" name="edit-op" value={o} checked={op === o} onChange={() => setOp(o)} />
              {E.ops[o]}
            </label>
          ))}
        </fieldset>
        <div className="grid-2">
          {(op === "trim" || op === "regenerate") && (
            <>
              {num("edit-start", E.start, start, setStart, currentLength)}
              {num("edit-end", E.end, end, setEnd, currentLength)}
            </>
          )}
          {op === "fade" && (
            <>
              {num("edit-fade-in", E.fadeIn, fadeIn, setFadeIn, 10)}
              {num("edit-fade-out", E.fadeOut, fadeOut, setFadeOut, 10)}
            </>
          )}
          {op === "extend" && num("edit-seconds", E.seconds, seconds, setSeconds, 60)}
        </div>
        {(op === "extend" || op === "regenerate") && (
          <div className="field">
            <label htmlFor="edit-prompt">{E.prompt}</label>
            <input id="edit-prompt" className="input" maxLength={300} value={prompt} onChange={(e) => setPrompt(e.target.value)} />
          </div>
        )}
        <div className="cluster">
          <button type="submit" className="btn btn-primary" disabled={busy || processing}>
            {processing ? E.working : E.apply}
          </button>
          <span className="small muted">{E.cost[op]}</span>
        </div>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
      </form>

      {versions.length > 0 && (
        <div className="stack">
          <h3 style={{ margin: 0 }}>{E.versions}</h3>
          <ol className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }} aria-live="polite">
            {[...versions].reverse().map((v) => (
              <li key={v.version} className="card tight stack" style={{ gap: "var(--space-2)" }}>
                <div className="spread">
                  <span>
                    <strong>{E.version(v.version)}</strong> · {v.op === "original" ? t.song.take(take) : E.ops[v.op]}
                    {v.current && <span className="badge badge-accent" style={{ marginInlineStart: "var(--space-2)" }}>{E.currentTag}</span>}
                  </span>
                  <span className="small muted">
                    {E.status[v.status]}
                    {v.durationSeconds != null && ` · ${E.length(clock(v.durationSeconds))}`}
                  </span>
                </div>
                {v.seam && v.status === "ready" && (
                  <p className={v.seam.passed ? "small muted" : "small error"} style={{ margin: 0 }}>
                    {v.seam.passed ? E.seamOk : E.seamWarn}
                  </p>
                )}
                {v.status === "ready" && v.audioUrl && (
                  <div className="cluster">
                    {playing === v.version ? (
                      <div style={{ flex: 1 }}>
                        <Player src={v.audioUrl} title={title} takeLabel={E.version(v.version)} compact locale={locale} />
                      </div>
                    ) : (
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setPlaying(v.version)}>
                        {E.play}
                      </button>
                    )}
                    {!v.current && (
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => restore(v.version)} disabled={processing}>
                        {E.restore}
                      </button>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  );
}
