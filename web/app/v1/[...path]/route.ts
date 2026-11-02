// Forwards the browser's API calls to the core API at runtime (API_ORIGIN), so the browser only
// talks to one origin and the session cookie stays first-party. Only the calls in lib/apiRoutes.ts
// get through. Live status (server-sent events) and audio are streamed, not buffered.

import { isAllowed } from "@/lib/apiRoutes";

const FORWARDED_HEADERS = ["content-type", "cookie", "cf-ipcountry", "cf-connecting-ip", "accept-language"];
const RETURNED_HEADERS = ["content-type", "content-length", "cache-control", "location"];

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
    body: request.method === "GET" ? undefined : await request.text(),
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
