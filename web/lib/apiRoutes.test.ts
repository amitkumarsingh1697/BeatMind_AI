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

test("forwards the Sprint 3 calls", () => {
  assert.ok(isAllowed("PATCH", "me"));
  assert.ok(isAllowed("POST", "me/mfa/verify"));
  assert.ok(isAllowed("POST", "lyrics"));
  assert.ok(isAllowed("GET", `lyrics/${ID}`));
  assert.ok(isAllowed("POST", `moderation/cases/${ID}/appeal`));
  assert.ok(isAllowed("POST", `songs/${ID}/takes/1/appeal`));
  assert.ok(isAllowed("POST", `songs/${ID}/commercial-licence`));
  assert.ok(isAllowed("GET", "certificates/BM-7K2Q-9XWM-4D1R"));
  assert.ok(isAllowed("POST", `admin/moderation/${ID}/decision`));
  assert.ok(isAllowed("GET", `admin/songs/${ID}/takes/2/audio`));
});

test("refuses Sprint 3 lookalikes", () => {
  assert.ok(!isAllowed("POST", "me/mfa/disable"));
  assert.ok(!isAllowed("GET", "certificates/bm-7k2q-9xwm-4d1r"));
  assert.ok(!isAllowed("GET", "certificates/BM-7K2Q-9XWM-4D1R/../x"));
  assert.ok(!isAllowed("PATCH", "lyrics"));
});

test("forwards the Sprint 4 calls", () => {
  assert.ok(isAllowed("POST", "reports"));
  assert.ok(isAllowed("POST", `songs/${ID}/takedown/appeal`));
  assert.ok(isAllowed("GET", "admin/takedowns"));
  assert.ok(isAllowed("POST", `admin/takedowns/${ID}/appeal-decision`));
  assert.ok(isAllowed("GET", "me/export"));
  assert.ok(isAllowed("DELETE", "me"));
  assert.ok(isAllowed("POST", `songs/${ID}/shares`));
  assert.ok(isAllowed("DELETE", `songs/${ID}/shares/${ID}`));
  assert.ok(isAllowed("GET", "shared/AbCdEfGhIjKlMnOpQrStUvWxYz012345"));
  assert.ok(isAllowed("GET", "shared/AbCdEfGhIjKlMnOpQrStUvWxYz012345/audio"));
});

test("refuses Sprint 4 lookalikes", () => {
  assert.ok(!isAllowed("GET", "reports"));
  assert.ok(!isAllowed("DELETE", `songs/${ID}/shares`));
  assert.ok(!isAllowed("GET", "shared/short"));
  assert.ok(!isAllowed("GET", "shared/AbCdEfGhIjKlMnOpQrStUvWxYz012345/../../me"));
  assert.ok(!isAllowed("POST", `admin/takedowns/${ID}/delete`));
});

test("forwards the Sprint 5 calls", () => {
  assert.ok(isAllowed("GET", "billing"));
  assert.ok(isAllowed("POST", "checkout"));
  assert.ok(isAllowed("POST", "billing/cancel"));
  assert.ok(isAllowed("POST", "billing/simulate"));
  assert.ok(isAllowed("POST", "webhooks/razorpay"));
  assert.ok(isAllowed("POST", `songs/${ID}/edits`));
  assert.ok(isAllowed("GET", `songs/${ID}/takes/1/versions`));
  assert.ok(isAllowed("GET", `songs/${ID}/takes/1/versions/3/audio`));
  assert.ok(isAllowed("POST", `songs/${ID}/takes/2/versions/1/restore`));
  assert.ok(isAllowed("POST", "verify"));
  assert.ok(isAllowed("GET", `verify/${ID}`));
  assert.ok(isAllowed("GET", "admin/fallback"));
});

test("refuses Sprint 5 lookalikes", () => {
  assert.ok(!isAllowed("GET", "checkout"));
  assert.ok(!isAllowed("POST", "billing/refund"));
  assert.ok(!isAllowed("GET", "webhooks/razorpay"));
  assert.ok(!isAllowed("GET", "verify/not-a-uuid"));
  assert.ok(!isAllowed("DELETE", `songs/${ID}/takes/1/versions/2`));
  assert.ok(!isAllowed("GET", `songs/${ID}/takes/1/versions/0/audio`));
});

test("forwards the Sprint 6 calls", () => {
  assert.ok(isAllowed("POST", "webhooks/paddle"));
  assert.ok(isAllowed("GET", `songs/${ID}/takes/1/stems`));
  assert.ok(isAllowed("POST", `songs/${ID}/takes/2/stems`));
  assert.ok(isAllowed("POST", `songs/${ID}/takes/1/stems/vocals/downloads`));
  assert.ok(isAllowed("GET", "downloads/stems/abc_DEF-1.sig-2"));
});

test("refuses Sprint 6 lookalikes", () => {
  assert.ok(!isAllowed("GET", "webhooks/paddle"));
  assert.ok(!isAllowed("POST", `songs/${ID}/takes/1/stems/guitar/downloads`));
  assert.ok(!isAllowed("DELETE", `songs/${ID}/takes/1/stems`));
  assert.ok(!isAllowed("GET", "downloads/stems/../me"));
});
