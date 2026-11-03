import { NextResponse, type NextRequest } from "next/server";

// "/" has no page of its own: send visitors to the language they chose before (cookie set by the
// language switch, US-010), else Hindi if their browser prefers it, else English.
export function proxy(request: NextRequest) {
  const chosen = request.cookies.get("bm_locale")?.value;
  const accept = request.headers.get("accept-language") ?? "";
  const locale = chosen === "hi" || chosen === "en" ? chosen : /^hi\b/i.test(accept.trim()) ? "hi" : "en";
  const url = request.nextUrl.clone();
  url.pathname = `/${locale}`;
  return NextResponse.redirect(url);
}

export const config = { matcher: "/" };
