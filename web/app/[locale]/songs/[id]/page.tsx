import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { isLocale } from "@/lib/messages";
import SongPage from "./SongPage";

export const metadata = { title: "Song · BeatMind AI" };

// Song page (US-023 player, US-026 downloads, US-019 history): owner view.
export default async function Page({ params }: { params: Promise<{ locale: string; id: string }> }) {
  const { locale, id } = await params;
  if (!isLocale(locale) || !/^[0-9a-f-]{36}$/.test(id)) notFound();
  return (
    <AppShell locale={locale}>
      <SongPage locale={locale} id={id} />
    </AppShell>
  );
}
