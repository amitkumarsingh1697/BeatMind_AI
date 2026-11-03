import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { appText } from "@/lib/i18n";
import { isLocale } from "@/lib/messages";

export const metadata = { title: "Help · BeatMind AI" };

// Help, linked from the same place on every page (UX-A11Y-16).
export default async function HelpPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const t = appText(locale).help;
  return (
    <AppShell locale={locale}>
      <header className="page-head">
        <p className="eyebrow">{t.eyebrow}</p>
        <h1>{t.heading}</h1>
        <p>{t.intro}</p>
      </header>
      <div className="stack">
        {t.questions.map(({ q, a }) => (
          <details key={q} className="card flat tight">
            <summary style={{ cursor: "pointer", fontWeight: 700, minBlockSize: "var(--tap)", display: "flex", alignItems: "center" }}>{q}</summary>
            <p style={{ marginBlockStart: "var(--space-2)" }}>{a}</p>
          </details>
        ))}
      </div>
    </AppShell>
  );
}
