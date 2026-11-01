import { test } from "node:test";
import assert from "node:assert/strict";
import { countGraphemes } from "./graphemes.ts";

test("counts a consonant with its vowel sign as one character", () => {
  assert.equal(countGraphemes("कि"), 1);
  assert.equal(countGraphemes("lofi"), 4);
  assert.equal(countGraphemes(""), 0);
});
