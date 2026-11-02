import { test } from "node:test";
import assert from "node:assert/strict";
import { clock, languageName, languageTag, spokenTime } from "./format.ts";

test("clock shows minutes and seconds", () => {
  assert.equal(clock(0), "0:00");
  assert.equal(clock(65.9), "1:05");
  assert.equal(clock(Number.NaN), "0:00");
});

test("spoken time reads naturally for screen readers", () => {
  assert.equal(spokenTime(65), "1 minute 5 seconds");
  assert.equal(spokenTime(1), "1 second");
});

test("TC-REQ-F-08-01 language tags follow BCP 47, Hinglish is Hindi in Latin script", () => {
  assert.equal(languageTag("hinglish"), "hi-Latn");
  assert.equal(languageTag("hi"), "hi");
  assert.equal(languageName("hi-Latn"), "Hinglish");
});
