import { notFound } from "next/navigation";
import { isLocale } from "@/lib/messages";
import Studio from "./Studio";

export const metadata = { title: "Make a song · BeatMind AI" };

// Developer preview of the first song flow (Sprint 1). English only until the Hindi interface
// lands with US-010; the Hindi route shows the same English screen for now.
export default async function CreatePage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <main>
      <header>
        <h1>Make a song</h1>
        <p className="soon">Developer preview. Describe a vibe and get two takes.</p>
      </header>
      <Studio locale={locale} />
    </main>
  );
}
