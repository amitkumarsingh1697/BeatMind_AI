import { test } from "node:test";
import assert from "node:assert/strict";
import { checkoutResult, liveSubscription, PADDLE_TEST_OUTCOMES, TEST_OUTCOMES, testOutcomes, type BillingSummary } from "./billing.ts";

const base: BillingSummary = { plan: "free", billingCountry: "IN", mode: "stub", keyId: null, subscription: null, periods: [] };
const sub = (status: string) =>
  ({ id: "s", plan: "creator", status, billingCountry: "IN", currency: "INR", monthly: 149, cancelAtPeriodEnd: false, currentStart: null, currentEnd: null, endedAt: null, createdAt: "" }) as BillingSummary["subscription"];

test("only authenticated, active and pending subscriptions give a plan", () => {
  assert.equal(liveSubscription(null), null);
  assert.equal(liveSubscription(base), null);
  for (const s of ["authenticated", "active", "pending"]) assert.ok(liveSubscription({ ...base, subscription: sub(s) }));
  for (const s of ["created", "halted", "cancelled", "completed", "expired"]) assert.equal(liveSubscription({ ...base, subscription: sub(s) }), null);
});

test("stub checkout outcomes map to what the person is told", () => {
  assert.equal(checkoutResult(["authenticated", "settled"]), "paid");
  assert.equal(checkoutResult(["authenticated", "billing_country_mismatch"]), "mismatch");
  assert.equal(checkoutResult(["pending"]), "pending");
});

test("every test outcome is one the API simulates", () => {
  const api = ["charged", "foreign_card", "renew", "pending", "halted", "dispute_lost", "refund"];
  for (const { outcome } of [...Object.values(TEST_OUTCOMES), ...Object.values(PADDLE_TEST_OUTCOMES)]) assert.ok(api.includes(outcome));
});

test("outside India the test payment has no UPI", () => {
  assert.ok(Object.values(testOutcomes("paddle")).every((o) => o.method === "card"));
  assert.ok(Object.values(testOutcomes("razorpay")).some((o) => o.method === "upi"));
});
