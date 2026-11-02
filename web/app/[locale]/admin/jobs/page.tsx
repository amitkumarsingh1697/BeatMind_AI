import { notFound } from "next/navigation";
import { isLocale } from "@/lib/messages";
import DeadJobs from "./DeadJobs";

export const metadata = { title: "Failed jobs · BeatMind AI" };

// Dead-letter view (US-013). The API allows only admins; the full console is US-031.
export default async function AdminJobsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  return (
    <main>
      <h1>Failed jobs</h1>
      <p className="soon">Jobs that failed every retry. Retrying puts a job back in the queue with fresh attempts.</p>
      <DeadJobs />
    </main>
  );
}
