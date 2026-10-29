import type { ReactNode } from "react";
import { notFound } from "next/navigation";
import { isLocale, LOCALES } from "@/lib/messages";
import "../globals.css";

export const metadata = {
  title: "BeatMind AI",
  description: "Original songs in your language, at your price, that you own.",
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
