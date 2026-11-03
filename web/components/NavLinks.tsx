"use client";

import { usePathname } from "next/navigation";
import type { Locale } from "@/lib/messages";
import { appText } from "@/lib/i18n";
import LanguageSwitch from "./LanguageSwitch";

const PATHS = ["create", "library", "help"] as const;

export default function NavLinks({ locale }: { locale: Locale }) {
  const pathname = usePathname() ?? "";
  const t = appText(locale).shell;
  return (
    <nav className="nav" aria-label={t.mainNav}>
      {PATHS.map((path) => {
        const href = `/${locale}/${path}`;
        const current = pathname === href || pathname.startsWith(`${href}/`) || (path === "library" && pathname.startsWith(`/${locale}/songs/`));
        return (
          <a key={path} href={href} aria-current={current ? "page" : undefined}>
            {t.nav[path]}
          </a>
        );
      })}
      <LanguageSwitch locale={locale} />
    </nav>
  );
}
