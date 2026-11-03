import type { ReactNode } from "react";
import type { Locale } from "@/lib/messages";
import { Logo } from "./Icons";
import NavLinks from "./NavLinks";
import LanguageSwitch from "./LanguageSwitch";
import { appText } from "@/lib/i18n";

/**
 * Page frame for every screen: skip link, header with the brand and navigation, and a footer with
 * the help link in the same place on every page (UX-A11Y-16).
 */
export default function AppShell({
  locale,
  children,
  variant = "app",
  wide = false,
}: {
  locale: Locale;
  children: ReactNode;
  variant?: "app" | "marketing";
  wide?: boolean;
}) {
  const t = appText(locale).shell;
  return (
    <>
      <a className="skip-link" href="#main">
        {t.skip}
      </a>
      <header className="shell-header">
        <div className="shell-bar">
          <a className="brand" href={variant === "app" ? `/${locale}/create` : `/${locale}`}>
            <Logo />
            <span>
              BeatMind <span className="brand-ai">AI</span>
            </span>
          </a>
          {variant === "app" ? (
            <NavLinks locale={locale} />
          ) : (
            <nav className="nav" aria-label={t.languageNav}>
              <LanguageSwitch locale={locale} />
            </nav>
          )}
        </div>
      </header>
      <main id="main" className={wide ? "page wide" : "page"} tabIndex={-1}>
        {children}
      </main>
      <footer className="shell-footer">
        <div className="shell-bar">
          <span>{t.footer}</span>
          <a href={`/${locale}/terms`} style={{ marginInlineStart: "auto" }}>
            {t.terms}
          </a>
          <a href={`/${locale}/help`}>{t.help}</a>
        </div>
      </footer>
    </>
  );
}
