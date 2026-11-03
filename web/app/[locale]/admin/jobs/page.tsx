import { redirect } from "next/navigation";

// The Sprint 1 address of the admin console; the console now lives at /{locale}/admin.
export default async function AdminJobsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  redirect(`/${locale === "hi" ? "hi" : "en"}/admin`);
}
