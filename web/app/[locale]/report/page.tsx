import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { appText } from "@/lib/i18n";
import { isLocale } from "@/lib/messages";
import ReportForm from "./ReportForm";

export const metadata = { title: "Report a song · BeatMind AI" };

// Report a song (US-032, API-013, Copyright and Takedown Policy section 2). No account needed.
export default async function ReportPage({
  params,
  searchParams,
}: {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ target?: string }>;
}) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const { target } = await searchParams;
  const R = appText(locale).report;
  return (
    <AppShell locale={locale}>
      <header className="page-head">
        <p className="eyebrow">{R.eyebrow}</p>
        <h1>{R.heading}</h1>
        <p>{R.intro}</p>
      </header>
      <ReportForm locale={locale} target={typeof target === "string" ? target.slice(0, 500) : ""} />
    </AppShell>
  );
}
