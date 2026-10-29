import { notFound } from "next/navigation";
import { isLocale, messages } from "@/lib/messages";
import Waitlist from "./Waitlist";

export default async function Page({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const t = messages[locale];
  return (
    <main>
      <nav className="lang">
        <a href={`/${t.otherLang.code}`} hrefLang={t.otherLang.code}>
          {t.otherLang.label}
        </a>
      </nav>
      <header>
        <h1>{t.title}</h1>
        <p className="tagline">{t.tagline}</p>
        <p className="soon">{t.comingSoon}</p>
      </header>
      <ul className="points">
        {t.points.map((p) => (
          <li key={p}>{p}</li>
        ))}
      </ul>
      <Waitlist locale={locale} />
    </main>
  );
}
