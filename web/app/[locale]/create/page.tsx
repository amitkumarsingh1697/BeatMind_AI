import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { isLocale } from "@/lib/messages";
import Studio from "./Studio";

export const metadata = { title: "Create · BeatMind AI" };

// Create screen (US-012, US-017). English only until the Hindi interface lands with US-010.
export default async function CreatePage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <AppShell locale={locale}>
      <Studio locale={locale} />
    </AppShell>
  );
}
