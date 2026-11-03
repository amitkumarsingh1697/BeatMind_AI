import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { appText } from "@/lib/i18n";
import { isLocale } from "@/lib/messages";
import { termsText } from "@/lib/terms";

export const metadata = { title: "Beta terms · BeatMind AI" };

// Closed beta terms (carry-over from Sprint 2, version 2026-11-beta), linked from the profile step
// and the footer of every page.
export default async function TermsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const t = appText(locale).terms;
  const terms = termsText(locale);
  return (
    <AppShell locale={locale}>
      <article className="stack-lg prose">
        <header className="page-head">
          <p className="eyebrow">{t.eyebrow}</p>
          <h1>{t.heading}</h1>
          <p className="small muted">{terms.updated}</p>
          <p>{terms.intro}</p>
        </header>
        {terms.sections.map((section) => (
          <section key={section.heading} className="stack">
            <h2>{section.heading}</h2>
            <ul className="stack">
              {section.points.map((point) => (
                <li key={point}>{point}</li>
              ))}
            </ul>
          </section>
        ))}
        <p className="notice">{terms.contact}</p>
      </article>
    </AppShell>
  );
}
