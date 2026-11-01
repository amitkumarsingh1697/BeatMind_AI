"use client";

import { useCallback, useEffect, useState } from "react";

type Job = { id: string; kind: string; attempts: number; error: string | null; createdAt: string; updatedAt: string };

export default function DeadJobs() {
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    const r = await fetch("/v1/admin/jobs?status=dead", { cache: "no-store" });
    if (r.status === 401 || r.status === 403) {
      setMessage("Only admins can see this page. Sign in with an admin account.");
      setJobs([]);
      return;
    }
    setJobs(r.ok ? await r.json() : []);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function retry(id: string) {
    const r = await fetch(`/v1/admin/jobs/${id}/retry`, { method: "POST" });
    setMessage(r.ok ? "Job queued again." : `Retry failed (${r.status}).`);
    load();
  }

  if (jobs === null) return <p aria-busy="true">Loading…</p>;
  return (
    <section>
      {message && <p role="status">{message}</p>}
      {jobs.length === 0 && !message && <p>No failed jobs.</p>}
      <ul className="songs">
        {jobs.map((j) => (
          <li key={j.id} className="song">
            <p className="prompt">
              {j.kind} job <code>{j.id}</code>, {j.attempts} attempts, last tried {new Date(j.updatedAt).toLocaleString()}
            </p>
            <p className="note">{j.error ?? "No error recorded"}</p>
            <button type="button" onClick={() => retry(j.id)}>Retry</button>
          </li>
        ))}
      </ul>
    </section>
  );
}
