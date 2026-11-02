"use client";

import { useEffect, useRef, useState } from "react";

const SITE_KEY = process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY ?? "";

declare global {
  interface Window {
    turnstile?: {
      render: (el: HTMLElement, opts: { sitekey: string; callback: (t: string) => void; appearance?: string }) => string;
      reset: (id?: string) => void;
    };
  }
}

/**
 * Cloudflare Turnstile in its non-interactive mode where possible (UX-A11Y-13, REQ-SEC-09). Off
 * when no site key is set (local development). Returns a ref for the widget, the latest token and
 * a reset for after each use (tokens are single-use).
 */
export function useTurnstile() {
  const ref = useRef<HTMLDivElement>(null);
  const [token, setToken] = useState("");
  const widget = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (!SITE_KEY || !ref.current) return;
    const el = ref.current;
    const render = () => {
      widget.current = window.turnstile?.render(el, { sitekey: SITE_KEY, callback: setToken, appearance: "interaction-only" });
    };
    if (window.turnstile) return render();
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js";
    script.async = true;
    script.onload = render;
    document.head.appendChild(script);
  }, []);

  return {
    enabled: SITE_KEY !== "",
    ref,
    token,
    reset: () => {
      setToken("");
      window.turnstile?.reset(widget.current);
    },
  };
}
