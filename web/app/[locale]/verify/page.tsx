import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { appText } from "@/lib/i18n";
import { isLocale } from "@/lib/messages";
import VerifyForms from "./VerifyForms";

export const metadata = { title: "Verify a song · BeatMind AI" };

// The public verify page (US-021, REQ-F-32, API-011). No account needed.
export default async function VerifyPage({
  params,
  searchParams,
}: {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ certificate?: string }>;
}) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const { certificate } = await searchParams;
  const V = appText(locale).verify;
  return (
    <AppShell locale={locale}>
      <header className="page-head">
        <p className="eyebrow">{V.eyebrow}</p>
        <h1>{V.heading}</h1>
        <p>{V.intro}</p>
      </header>
      <VerifyForms locale={locale} certificate={typeof certificate === "string" ? certificate.slice(0, 40) : ""} />
    </AppShell>
  );
}
