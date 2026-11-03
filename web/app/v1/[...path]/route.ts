// Forwards the browser's API calls to the core API at runtime (API_ORIGIN), so the browser only
// talks to one origin and the session cookie stays first-party. Only the calls in lib/apiRoutes.ts
// get through. Live status (server-sent events) and audio are streamed, not buffered.

import { isAllowed } from "@/lib/apiRoutes";

const FORWARDED_HEADERS = [
  "content-type",
  "cookie",
  "cf-ipcountry",
  "cf-connecting-ip",
  "accept-language",
  "range",
  // Razorpay webhooks (US-034): the signature covers the raw body, so it is forwarded byte for byte
  "x-razorpay-signature",
  "x-razorpay-event-id",
  // Paddle notifications (US-035): signed over the timestamp and the raw body
  "paddle-signature",
];
const RETURNED_HEADERS = [
  "content-type",
  "content-length",
  "cache-control",
  "location",
  // Seeking in the player and file names for downloads (US-023, US-026)
  "accept-ranges",
  "content-range",
  "content-disposition",
];

async function bodyOf(request: Request): Promise<ArrayBuffer | undefined> {
  const bytes = await request.arrayBuffer();
  return bytes.byteLength > 0 ? bytes : undefined;
}

async function forward(request: Request, path: string[]): Promise<Response> {
  const joined = path.join("/");
  if (!isAllowed(request.method, joined)) {
    return new Response(null, { status: 404 });
  }
  const origin = process.env.API_ORIGIN ?? "http://localhost:8080";
  const search = new URL(request.url).search;
  const headers = new Headers();
  for (const name of FORWARDED_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const upstream = await fetch(`${origin}/v1/${joined}${search}`, {
    method: request.method,
    headers,
    // Bytes, not text: file uploads on the verify page are multipart (US-021) and webhook
    // signatures cover the exact body (US-034). DELETE /v1/me carries a confirmation body (US-011).
    body: request.method === "GET" ? undefined : await bodyOf(request),
    cache: "no-store",
    redirect: "manual",
    signal: request.signal,
  });
  const out = new Headers();
  for (const name of RETURNED_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) out.set(name, value);
  }
  for (const cookie of upstream.headers.getSetCookie()) {
    out.append("set-cookie", cookie);
  }
  if (out.get("content-type")?.startsWith("text/event-stream")) {
    out.set("cache-control", "no-cache, no-transform");
    out.set("x-accel-buffering", "no");
  }
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

type Ctx = { params: Promise<{ path: string[] }> };

export async function GET(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}

export async function POST(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}

export async function PATCH(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}

export async function DELETE(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}
