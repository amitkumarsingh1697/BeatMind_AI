import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { appText } from "@/lib/i18n";
import { isLocale } from "@/lib/messages";
import Pricing from "./Pricing";

export const metadata = { title: "Plans · BeatMind AI" };

// Plans and checkout (US-033 prices by billing country, US-034 Razorpay checkout).
export default async function PricingPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const B = appText(locale).billing;
  return (
    <AppShell locale={locale}>
      <header className="page-head">
        <p className="eyebrow">{B.eyebrow}</p>
        <h1>{B.heading}</h1>
        <p>{B.intro}</p>
      </header>
      <Pricing locale={locale} />
    </AppShell>
  );
}
