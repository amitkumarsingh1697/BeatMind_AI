"use client";

import { usePathname } from "next/navigation";
import type { Locale } from "@/lib/messages";
import { appText } from "@/lib/i18n";

export const LOCALE_COOKIE = "bm_locale";

/**
 * Switches the interface language and stays on the same screen (US-010). The choice is kept in a
 * cookie for the next visit to "/" and, when signed in, on the account (PATCH /v1/me), so emails
 * and other devices use it too.
 */
export default function LanguageSwitch({ locale }: { locale: Locale }) {
  const pathname = usePathname() ?? `/${locale}`;
  const t = appText(locale).shell;
  const target = t.switchLang;
  const href = pathname.replace(/^\/(en|hi)(?=\/|$)/, `/${target}`) || `/${target}`;

  function remember() {
    document.cookie = `${LOCALE_COOKIE}=${target}; path=/; max-age=31536000; samesite=lax`;
    // Best effort: signed-out visitors get a 401, which is fine.
    fetch("/v1/me", {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ locale: target }),
      keepalive: true,
    }).catch(() => undefined);
  }

  return (
    <a className="lang-switch" href={href} hrefLang={target} lang={target} onClick={remember}>
      {t.switchTo}
    </a>
  );
}
