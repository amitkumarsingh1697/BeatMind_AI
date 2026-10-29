import { NextResponse, type NextRequest } from "next/server";

// "/" has no page of its own: send visitors to Hindi if their browser prefers it, else English.
export function proxy(request: NextRequest) {
  const accept = request.headers.get("accept-language") ?? "";
  const locale = /^hi\b/i.test(accept.trim()) ? "hi" : "en";
  const url = request.nextUrl.clone();
  url.pathname = `/${locale}`;
  return NextResponse.redirect(url);
}

export const config = { matcher: "/" };
