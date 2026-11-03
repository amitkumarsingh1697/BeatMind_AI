import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { isLocale } from "@/lib/messages";
import AdminConsole from "./Console";

export const metadata = { title: "Admin · BeatMind AI", robots: { index: false } };

// Admin console (US-031): second factor first, then users, moderation queue, audit, caps, plans,
// ledger check and failed jobs. Internal tool, English only.
export default async function AdminPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <AppShell locale={locale} wide>
      <AdminConsole locale={locale} />
    </AppShell>
  );
}
