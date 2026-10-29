// Forwards the waitlist's API calls to the core API at runtime (API_ORIGIN), so the browser only
// talks to one origin. Only the endpoints the waitlist needs are allowed through.

const ALLOWED = new Set(["GET pricing", "POST waitlist", "POST waitlist/visits"]);
const FORWARDED_HEADERS = ["content-type", "cf-ipcountry", "cf-connecting-ip", "accept-language"];

async function forward(request: Request, path: string[]): Promise<Response> {
  const joined = path.join("/");
  if (!ALLOWED.has(`${request.method} ${joined}`)) {
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
  });
  return new Response(upstream.body, {
    status: upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "application/json" },
  });
}

type Ctx = { params: Promise<{ path: string[] }> };

export async function GET(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}

export async function POST(request: Request, ctx: Ctx) {
  return forward(request, (await ctx.params).path);
}
