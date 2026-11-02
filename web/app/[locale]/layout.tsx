import type { ReactNode } from "react";
import { notFound } from "next/navigation";
import { isLocale, LOCALES } from "@/lib/messages";
// Self-hosted fonts, split by script with unicode-range, so a page only downloads the Latin or
// Devanagari files it uses (UX spec section 8, performance budget).
import "@fontsource-variable/baloo-2/wght.css";
import "@fontsource/mukta/400.css";
import "@fontsource/mukta/600.css";
import "@fontsource/mukta/700.css";
import "../globals.css";

export const metadata = {
  title: "BeatMind AI",
  description: "Original songs in your language, at your price, that you own.",
};

export const viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: "#0e0b16" },
    { media: "(prefers-color-scheme: light)", color: "#fff8ef" },
  ],
};

export function generateStaticParams() {
  return LOCALES.map((locale) => ({ locale }));
}

export default async function LocaleLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <html lang={locale}>
      <body>{children}</body>
    </html>
  );
}
