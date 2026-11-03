import { test } from "node:test";
import assert from "node:assert/strict";
import { allStrings, appText, DICTIONARIES, errorText } from "./i18n.ts";
import { TERMS } from "./terms.ts";

// US-010: the Hindi interface has every string the English one has.
function shape(value: unknown, path = ""): string[] {
  if (Array.isArray(value)) return [`${path}[${value.length}]`, ...value.flatMap((v, i) => shape(v, `${path}[${i}]`))];
  if (value && typeof value === "object") {
    return Object.keys(value)
      .sort()
      .flatMap((k) => shape((value as Record<string, unknown>)[k], path ? `${path}.${k}` : k));
  }
  return [`${path}:${typeof value}`];
}

test("Hindi and English dictionaries have the same keys and shapes", () => {
  assert.deepEqual(shape(DICTIONARIES.hi), shape(DICTIONARIES.en));
  assert.deepEqual(shape(TERMS.hi), shape(TERMS.en));
});

test("no string is empty", () => {
  for (const dict of [DICTIONARIES.en, DICTIONARIES.hi, TERMS.en, TERMS.hi]) {
    for (const s of allStrings(dict)) assert.ok(s.trim().length > 0, "empty string in a dictionary");
  }
});

test("Hindi screens are written in Devanagari, not left in English", () => {
  const hi = allStrings(DICTIONARIES.hi);
  const devanagari = hi.filter((s) => /[ऀ-ॿ]/.test(s));
  // Brand names, plan names, codes and file formats may stay in Latin letters; nearly everything else is Hindi.
  assert.ok(devanagari.length / hi.length > 0.8, `${devanagari.length} of ${hi.length} Hindi strings use Devanagari`);
});

test("the AI label is always shown in both languages", () => {
  assert.equal(appText("en").common.ai, "AI-generated");
  assert.equal(appText("hi").common.ai, "AI से बना");
});

// REQ-F-26: never promise copyright protection, claim-free use or a guarantee.
const FORBIDDEN = [
  /copyright[- ]protected/i,
  /guarantee/i,
  /claim[- ]free/i,
  /royalty[- ]free/i,
  /100%\s*(safe|original|yours)/i,
  /never get (a )?claim/i,
  /गारंटी/,
  /कॉपीराइट (से )?सुरक्षित/,
];

test("no copy promises copyright protection or claim-free use", () => {
  const strings = [DICTIONARIES.en, DICTIONARIES.hi, TERMS.en, TERMS.hi].flatMap((d) => allStrings(d));
  for (const s of strings) {
    for (const pattern of FORBIDDEN) assert.ok(!pattern.test(s), `"${s}" matches ${pattern}`);
  }
});

test("errors use the Hindi text for known codes and the API's English otherwise", () => {
  const p = { detail: "Specific English detail.", code: "licence_quota" };
  assert.equal(errorText("en", p), "Specific English detail.");
  assert.equal(errorText("hi", p), appText("hi").errors.licence_quota);
  assert.equal(errorText("hi", { detail: "Unknown.", code: "something_new" }), "Unknown.");
});
