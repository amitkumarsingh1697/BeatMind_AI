"use client";

import { usePathname } from "next/navigation";
import type { Locale } from "@/lib/messages";

const LINKS = [
  { path: "create", label: "Create" },
  { path: "library", label: "Library" },
  { path: "help", label: "Help" },
];

export default function NavLinks({ locale }: { locale: Locale }) {
  const pathname = usePathname() ?? "";
  return (
    <nav className="nav" aria-label="Main">
      {LINKS.map((l) => {
        const href = `/${locale}/${l.path}`;
        const current = pathname === href || pathname.startsWith(`${href}/`) || (l.path === "library" && pathname.startsWith(`/${locale}/songs/`));
        return (
          <a key={l.path} href={href} aria-current={current ? "page" : undefined}>
            {l.label}
          </a>
        );
      })}
    </nav>
  );
}
