import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { isLocale } from "@/lib/messages";
import Library from "./Library";

export const metadata = { title: "Library · BeatMind AI" };

// Library (US-022): every song the creator made, with search, rename and delete.
export default async function LibraryPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <AppShell locale={locale}>
      <Library locale={locale} />
    </AppShell>
  );
}
