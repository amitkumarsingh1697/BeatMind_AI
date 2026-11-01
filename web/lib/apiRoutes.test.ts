import { test } from "node:test";
import assert from "node:assert/strict";
import { isAllowed } from "./apiRoutes.ts";

const ID = "3f2b8c1e-9a4d-4e5f-8b6a-1c2d3e4f5a6b";

test("forwards the waitlist, sign-in and generation calls", () => {
  assert.ok(isAllowed("GET", "pricing"));
  assert.ok(isAllowed("POST", "auth/email/start"));
  assert.ok(isAllowed("GET", "auth/google/callback"));
  assert.ok(isAllowed("POST", "generations"));
  assert.ok(isAllowed("GET", `generations/${ID}/events`));
  assert.ok(isAllowed("GET", `generations/${ID}/takes/2/audio`));
  assert.ok(isAllowed("POST", `admin/jobs/${ID}/retry`));
});

test("refuses anything else", () => {
  assert.ok(!isAllowed("DELETE", "generations"));
  assert.ok(!isAllowed("GET", "generations/not-a-uuid"));
  assert.ok(!isAllowed("GET", `generations/${ID}/takes/0/audio`));
  assert.ok(!isAllowed("GET", "actuator/prometheus"));
  assert.ok(!isAllowed("GET", "../actuator/health"));
  assert.ok(!isAllowed("GET", "pricing/extra"));
});
