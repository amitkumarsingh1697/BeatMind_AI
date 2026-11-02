import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { MusicIcon, PenIcon, ShieldIcon, SparkleIcon } from "@/components/Icons";
import { isLocale, messages } from "@/lib/messages";
import Waitlist from "./Waitlist";

const ICONS = [PenIcon, SparkleIcon, MusicIcon, ShieldIcon];

export default async function Page({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const t = messages[locale];
  return (
    <AppShell locale={locale} variant="marketing" wide>
      <div className="stack-lg">
        <header className="hero">
          <p className="badge badge-accent" style={{ marginBlockEnd: "var(--space-4)" }}>
            {t.comingSoon}
          </p>
          <h1>
            <span className="gradient-text">{t.title}</span>
          </h1>
          <p className="tagline">{t.tagline}</p>
        </header>
        <ul className="feature-list">
          {t.points.map((p, i) => {
            const Icon = ICONS[i % ICONS.length];
            return (
              <li key={p} className="feature">
                <span className="icon">
                  <Icon size={28} />
                </span>
                <span>{p}</span>
              </li>
            );
          })}
        </ul>
        <Waitlist locale={locale} />
      </div>
    </AppShell>
  );
}
