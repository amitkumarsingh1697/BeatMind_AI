import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { isLocale } from "@/lib/messages";
import YourData from "./YourData";

export const metadata = { title: "Your data · BeatMind AI", robots: { index: false } };

// Your data (US-011, DPIA section 7): export everything, or delete the account.
export default async function SettingsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <AppShell locale={locale}>
      <YourData locale={locale} />
    </AppShell>
  );
}
