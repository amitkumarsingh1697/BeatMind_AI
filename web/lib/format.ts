// Display helpers shared by the app pages. Numbers and dates use the Intl API (REQ-NFR-09).

export function clock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const s = Math.floor(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

type TimeWords = { minute: (n: number) => string; second: (n: number) => string };

const ENGLISH_TIME: TimeWords = {
  minute: (n) => `${n} minute${n === 1 ? "" : "s"}`,
  second: (n) => `${n} second${n === 1 ? "" : "s"}`,
};

/** Spoken form for screen readers, e.g. "1 minute 5 seconds" or "1 मिनट 5 सेकंड". */
export function spokenTime(seconds: number, words: TimeWords = ENGLISH_TIME): string {
  const s = Math.max(0, Math.floor(seconds || 0));
  const m = Math.floor(s / 60);
  const r = s % 60;
  const parts = [];
  if (m) parts.push(words.minute(m));
  parts.push(words.second(r));
  return parts.join(" ");
}

const LANGUAGE_NAMES: Record<string, string> = { hi: "Hindi", "hi-Latn": "Hinglish", en: "English" };

export function languageName(tag: string | null | undefined, names: Record<string, string> = LANGUAGE_NAMES): string {
  return (tag && names[tag]) || names.en || "English";
}

/** BeatMind vocal language code to BCP 47 tag, for lang attributes (UX-A11Y-10). */
export function languageTag(code: string): string {
  return code === "hinglish" ? "hi-Latn" : code === "hi" ? "hi" : "en";
}

export function shortDate(iso: string, locale = "en-IN"): string {
  return new Intl.DateTimeFormat(locale, { day: "numeric", month: "short", year: "numeric" }).format(new Date(iso));
}

export function dateTime(iso: string, locale = "en-IN"): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
}

export function lengthLabel(
  seconds: number,
  words: { lengthSeconds: (n: number) => string; lengthMinutes: (n: number) => string } = {
    lengthSeconds: (n) => `${n} s`,
    lengthMinutes: (n) => `${n} min`,
  },
): string {
  return seconds < 60 ? words.lengthSeconds(seconds) : words.lengthMinutes(Math.round((seconds / 60) * 10) / 10);
}

/** Whole numbers in the Indian system (1,00,000), in Latin digits for both languages. */
export function count(n: number, locale = "en-IN"): string {
  return new Intl.NumberFormat(locale).format(n);
}

export type Problem = { detail: string; code: string | null; field?: string | null; caseId?: string | null };

/** The API's problem+json detail, or a plain fallback. Blocked requests also carry a review case. */
export async function problem(r: Response): Promise<Problem> {
  try {
    const body = await r.json();
    return { detail: body.detail ?? `Error ${r.status}`, code: body.code ?? null, field: body.field ?? null, caseId: body.caseId ?? null };
  } catch {
    return { detail: `Error ${r.status}`, code: null };
  }
}
