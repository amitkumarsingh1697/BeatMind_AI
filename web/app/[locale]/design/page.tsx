import { notFound } from "next/navigation";
import AiLabel from "@/components/AiLabel";
import AppShell from "@/components/AppShell";
import { DownloadIcon, MusicIcon, PenIcon, SparkleIcon } from "@/components/Icons";
import { isLocale } from "@/lib/messages";

export const metadata = { title: "Design system · BeatMind AI", robots: { index: false } };

const COLOURS = [
  ["--bg", "Background"],
  ["--surface", "Surface"],
  ["--surface-raised", "Raised"],
  ["--text", "Text"],
  ["--text-muted", "Muted text"],
  ["--accent", "Marigold (action)"],
  ["--highlight", "Rani pink (playhead)"],
  ["--ai", "Iris (AI label)"],
  ["--success", "Success"],
  ["--danger", "Danger"],
  ["--focus", "Focus ring"],
  ["--border-strong", "Control border"],
];

const TYPE = [
  ["--step-5", "Display", "Original songs"],
  ["--step-4", "Heading 1", "Make a song"],
  ["--step-2", "Heading 2", "आपकी भाषा में गाने"],
  ["--step-1", "Large text", "Raat ka safar, tere bina"],
  ["--step-0", "Body", "Two takes in about a minute. Keep the one you like."],
  ["--step--1", "Small", "1 credit = 1 take · failed takes are refunded"],
];

// Living style guide for developers (Sprint 2). The written rules are in
// docs/08-ux/visual-design.md. Not linked from the app and not indexed.
export default async function DesignPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <AppShell locale={locale} wide>
      <div className="stack-lg">
        <header className="page-head">
          <p className="eyebrow">Design system</p>
          <h1>BeatMind look and feel</h1>
          <p>Warm, bright and honest about AI. Marigold for action, rani pink for sound, iris for the AI label.</p>
        </header>

        <section className="stack" aria-labelledby="colour-heading">
          <h2 id="colour-heading">Colour</h2>
          <p className="muted">Tokens switch with the system light or dark setting. Text pairs pass 4.5:1; controls and focus 3:1.</p>
          <div className="swatches">
            {COLOURS.map(([token, name]) => (
              <div className="swatch" key={token}>
                <div className="chip-color" style={{ background: `var(${token})` }} />
                <p>
                  <strong>{name}</strong>
                  <br />
                  <code>{token}</code>
                </p>
              </div>
            ))}
          </div>
        </section>

        <section className="stack" aria-labelledby="type-heading">
          <h2 id="type-heading">Type</h2>
          <p className="muted">Baloo 2 for headings and Mukta for text. Both draw Latin and Devanagari, so Hindi never falls back to another font.</p>
          <div className="card flat stack">
            {TYPE.map(([token, name, sample], i) => (
              <div key={token} className="spread" style={{ alignItems: "baseline" }}>
                <span
                  lang={sample.match(/[ऀ-ॿ]/) ? "hi" : undefined}
                  style={{ fontSize: `var(${token})`, fontFamily: i < 3 ? "var(--font-display)" : "var(--font-text)", fontWeight: i < 3 ? 700 : 400, lineHeight: 1.2 }}
                >
                  {sample}
                </span>
                <code className="small muted">
                  {name} {token}
                </code>
              </div>
            ))}
          </div>
        </section>

        <section className="stack" aria-labelledby="buttons-heading">
          <h2 id="buttons-heading">Buttons and labels</h2>
          <div className="card flat stack">
            <div className="cluster">
              <button type="button" className="btn btn-primary">
                Make two takes <span className="cost">· 2 credits</span>
              </button>
              <button type="button" className="btn btn-secondary">
                <DownloadIcon size={18} /> MP3
              </button>
              <button type="button" className="btn btn-ghost">
                Cancel
              </button>
              <button type="button" className="btn btn-danger">
                Delete
              </button>
              <button type="button" className="btn btn-primary" disabled>
                Disabled
              </button>
            </div>
            <div className="cluster">
              <AiLabel />
              <span className="badge badge-success">Ready</span>
              <span className="badge badge-accent">Making</span>
              <span className="badge badge-danger">Failed, refunded</span>
              <span className="badge">Personal listening</span>
            </div>
          </div>
        </section>

        <section className="stack" aria-labelledby="form-heading">
          <h2 id="form-heading">Forms</h2>
          <div className="card flat">
            <form>
              <fieldset className="segmented">
                <legend className="visually-hidden">Mode</legend>
                <label>
                  <input type="radio" name="demo-mode" defaultChecked />
                  <span className="cluster">
                    <SparkleIcon size={18} /> Describe a vibe
                  </span>
                </label>
                <label>
                  <input type="radio" name="demo-mode" />
                  <span className="cluster">
                    <PenIcon size={18} /> Use my lyrics
                  </span>
                </label>
              </fieldset>
              <div className="field">
                <label htmlFor="demo-field">Your lyrics</label>
                <p className="hint" id="demo-hint">
                  Hint text sits under the label.
                </p>
                <textarea id="demo-field" className="textarea" aria-describedby="demo-hint demo-error" aria-invalid defaultValue="रात का सफ़र" lang="hi" />
                <p className="counter">8 / 3,000 characters</p>
                <p id="demo-error" className="error">
                  Errors say what went wrong and how to fix it.
                </p>
              </div>
            </form>
          </div>
        </section>

        <section className="stack" aria-labelledby="states-heading">
          <h2 id="states-heading">Notices and empty states</h2>
          <div className="grid-2">
            <p className="notice">Neutral notice for information.</p>
            <p className="notice notice-success">Saved. Free accounts now get 20 credits a day.</p>
            <p className="notice notice-danger">We could not make this song. Your credits were refunded.</p>
            <p className="notice notice-ai">Every download carries the AI label, C2PA credentials and a watermark.</p>
          </div>
          <div className="empty">
            <MusicIcon size={32} />
            <h3>No songs yet</h3>
            <p>Empty states say what will appear and offer the next step.</p>
          </div>
        </section>
      </div>
    </AppShell>
  );
}
