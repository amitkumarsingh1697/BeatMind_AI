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

test("forwards the Sprint 2 library, download and admin calls", () => {
  assert.ok(isAllowed("GET", "songs"));
  assert.ok(isAllowed("PATCH", `songs/${ID}`));
  assert.ok(isAllowed("DELETE", `songs/${ID}`));
  assert.ok(isAllowed("GET", `songs/${ID}/takes/1/audio`));
  assert.ok(isAllowed("POST", `songs/${ID}/takes/2/downloads`));
  assert.ok(isAllowed("GET", "downloads/YWJj.ZGVm-_1"));
  assert.ok(isAllowed("GET", "credits"));
  assert.ok(isAllowed("POST", "admin/caps"));
  assert.ok(isAllowed("GET", "admin/ledger/verify"));
});

test("refuses Sprint 2 lookalikes", () => {
  assert.ok(!isAllowed("DELETE", "songs"));
  assert.ok(!isAllowed("PATCH", `songs/${ID}/takes/1/audio`));
  assert.ok(!isAllowed("GET", "downloads/no-dot"));
  assert.ok(!isAllowed("GET", "downloads/a.b/../../admin"));
  assert.ok(!isAllowed("GET", `songs/${ID}/takes/1/downloads`));
});
