import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { isLocale } from "@/lib/messages";
import AdminConsole from "./DeadJobs";

export const metadata = { title: "Admin · BeatMind AI" };

// Admin (API-014, admin role only): failed jobs (US-013), daily cap and plans (US-030), ledger
// check (US-019). The full console is US-031.
export default async function AdminJobsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <AppShell locale={locale} wide>
      <AdminConsole locale={locale} />
    </AppShell>
  );
}
