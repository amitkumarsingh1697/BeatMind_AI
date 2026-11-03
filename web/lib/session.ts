"use client";

import { useCallback, useEffect, useState } from "react";

export type Me = {
  id: string;
  email: string;
  needsProfile: boolean;
  /** An admin email: the console still needs the second factor (adminVerified). */
  admin: boolean;
  adminVerified?: boolean;
  plan: "free" | "creator" | "pro";
  locale?: "en" | "hi";
  suspended?: boolean;
  termsVersion?: string | null;
};

/** The signed-in account: undefined while loading, null when signed out. */
export function useMe() {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const reload = useCallback(async () => {
    try {
      const r = await fetch("/v1/me", { cache: "no-store" });
      setMe(r.ok ? await r.json() : null);
    } catch {
      setMe(null);
    }
  }, []);
  useEffect(() => {
    reload();
  }, [reload]);
  return { me, setMe, reload };
}

export async function signOut() {
  await fetch("/v1/auth/logout", { method: "POST" });
  window.location.reload();
}

export const PLAN_NAMES: Record<Me["plan"], string> = { free: "Free", creator: "Creator", pro: "Pro" };
