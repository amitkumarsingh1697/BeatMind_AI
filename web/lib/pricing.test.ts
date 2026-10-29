import { test } from "node:test";
import assert from "node:assert/strict";
import { formatPrice, pickVariant } from "./pricing.ts";

test("formats rupees without decimals", () => {
  assert.equal(formatPrice(149, "INR", "en"), "₹149");
  assert.equal(formatPrice(2999, "INR", "en"), "₹2,999");
});

test("formats dollars with cents", () => {
  assert.equal(formatPrice(2.8, "USD", "en"), "US$2.80");
});

test("keeps a stored variant", () => {
  assert.equal(pickVariant("B", () => 0), "B");
});

test("assigns a random variant when none is stored", () => {
  assert.equal(pickVariant(null, () => 0.1), "A");
  assert.equal(pickVariant("junk", () => 0.9), "B");
});
