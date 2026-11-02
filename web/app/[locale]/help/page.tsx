import { notFound } from "next/navigation";
import AppShell from "@/components/AppShell";
import { isLocale } from "@/lib/messages";

export const metadata = { title: "Help · BeatMind AI" };

const QUESTIONS = [
  {
    q: "What is a credit?",
    a: "One credit makes one take. Every request makes two takes, so it uses 2 credits. Free accounts get a daily allowance that resets at midnight India time; paid plans get a monthly one.",
  },
  {
    q: "What happens if a song fails?",
    a: "You get your credits back automatically. If one take is held back by our safety check, its credit comes back too. You can see every refund in your history.",
  },
  {
    q: "Why does every song say AI-generated?",
    a: "Indian law and our own rules require AI-made audio to be labelled. Every song page, player and download shows the label. Downloaded files also carry it in their tags, in signed content credentials (C2PA) and in an inaudible watermark.",
  },
  {
    q: "Which files can I download?",
    a: "Free: MP3 at 192 kbps for personal listening. Creator and Pro: MP3 at 320 kbps and WAV at 48 kHz, 24-bit. Download links work for 15 minutes.",
  },
  {
    q: "Who owns my songs?",
    a: "On a paid plan you own the songs you make. On the free plan BeatMind owns them and you may listen to them personally. Free commercial licences come in a later update.",
  },
  {
    q: "Can I write in Hindi?",
    a: "Yes. Lyrics can be in Hindi (Devanagari), Hinglish (Hindi in English letters) or English, up to 3,000 characters. Search finds Hindi titles even when you type in English letters.",
  },
  {
    q: "How do I use the player with a keyboard?",
    a: "Press Tab to reach the play button, then Space or Enter to play or pause. On the position and volume sliders, the arrow keys move by 5 seconds or 10 percent. Nothing plays until you press play.",
  },
];

// Help, linked from the same place on every page (UX-A11Y-16).
export default async function HelpPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <AppShell locale={locale}>
      <header className="page-head">
        <p className="eyebrow">Help</p>
        <h1>Questions and answers</h1>
        <p>Short answers about credits, labels, downloads and rights.</p>
      </header>
      <div className="stack">
        {QUESTIONS.map(({ q, a }) => (
          <details key={q} className="card flat tight">
            <summary style={{ cursor: "pointer", fontWeight: 700, minBlockSize: "var(--tap)", display: "flex", alignItems: "center" }}>{q}</summary>
            <p style={{ marginBlockStart: "var(--space-2)" }}>{a}</p>
          </details>
        ))}
      </div>
    </AppShell>
  );
}
