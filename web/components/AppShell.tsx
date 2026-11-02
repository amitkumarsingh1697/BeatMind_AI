import type { ReactNode } from "react";
import type { Locale } from "@/lib/messages";
import { Logo } from "./Icons";
import NavLinks from "./NavLinks";

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
  const other = locale === "hi" ? { code: "en", label: "English" } : { code: "hi", label: "हिन्दी" };
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
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
            <nav className="nav" aria-label="Language">
              <a href={`/${other.code}`} hrefLang={other.code} lang={other.code}>
                {other.label}
              </a>
            </nav>
          )}
        </div>
      </header>
      <main id="main" className={wide ? "page wide" : "page"} tabIndex={-1}>
        {children}
      </main>
      <footer className="shell-footer">
        <div className="shell-bar">
          <span>BeatMind AI · every song is labelled as AI-generated</span>
          <a href={`/${locale}/help`} style={{ marginInlineStart: "auto" }}>
            Help
          </a>
        </div>
      </footer>
    </>
  );
}
