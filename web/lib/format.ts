// Display helpers shared by the app pages. Numbers and dates use the Intl API (REQ-NFR-09).

export function clock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const s = Math.floor(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/** Spoken form for screen readers, e.g. "1 minute 5 seconds". */
export function spokenTime(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds || 0));
  const m = Math.floor(s / 60);
  const r = s % 60;
  const parts = [];
  if (m) parts.push(`${m} minute${m === 1 ? "" : "s"}`);
  parts.push(`${r} second${r === 1 ? "" : "s"}`);
  return parts.join(" ");
}

const LANGUAGE_NAMES: Record<string, string> = { hi: "Hindi", "hi-Latn": "Hinglish", en: "English" };

export function languageName(tag: string | null | undefined): string {
  return (tag && LANGUAGE_NAMES[tag]) || "English";
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

export function lengthLabel(seconds: number): string {
  return seconds < 60 ? `${seconds} s` : `${Math.round((seconds / 60) * 10) / 10} min`;
}

/** The API's problem+json detail, or a plain fallback. */
export async function problem(r: Response): Promise<{ detail: string; code: string | null }> {
  try {
    const body = await r.json();
    return { detail: body.detail ?? `Error ${r.status}`, code: body.code ?? null };
  } catch {
    return { detail: `Error ${r.status}`, code: null };
  }
}
