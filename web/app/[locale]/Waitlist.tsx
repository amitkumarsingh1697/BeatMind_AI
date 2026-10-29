"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { CONSENT_TEXT_VERSION, messages, type Locale } from "@/lib/messages";
import { formatPrice, pickVariant, type PricingQuote } from "@/lib/pricing";

const VARIANT_KEY = "bm_price_variant";
const TURNSTILE_SITE_KEY = process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY ?? "";
// Must be set before the page goes live, so people can reach us about their data.
const CONTACT_EMAIL = process.env.NEXT_PUBLIC_CONTACT_EMAIL ?? "";

declare global {
  interface Window {
    turnstile?: { render: (el: HTMLElement, opts: { sitekey: string; callback: (t: string) => void }) => void };
  }
}

function readStoredVariant(): string | null {
  try {
    return window.localStorage.getItem(VARIANT_KEY);
  } catch {
    return null;
  }
}

function storeVariant(v: string) {
  try {
    window.localStorage.setItem(VARIANT_KEY, v);
  } catch {
    // Private mode: the variant simply won't persist.
  }
}

export default function Waitlist({ locale }: { locale: Locale }) {
  const t = messages[locale];
  const [quote, setQuote] = useState<PricingQuote | null>(null);
  const [state, setState] = useState<"idle" | "sending" | "done" | "error">("idle");
  const [turnstileToken, setTurnstileToken] = useState("");
  const turnstileRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const variant = pickVariant(readStoredVariant());
    storeVariant(variant);
    const params = new URLSearchParams(window.location.search);
    const query = new URLSearchParams({ variant });
    const country = params.get("country"); // lets the team preview other countries
    if (country) query.set("country", country);
    const source = params.get("utm_source") ?? "";

    fetch(`/v1/pricing?${query}`)
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((q: PricingQuote) => {
        setQuote(q);
        return fetch("/v1/waitlist/visits", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ country: q.country, priceVariant: q.variant, sourceChannel: source }),
        });
      })
      .catch(() => setQuote(null));
  }, []);

  useEffect(() => {
    if (!TURNSTILE_SITE_KEY || !turnstileRef.current) return;
    const el = turnstileRef.current;
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js";
    script.async = true;
    script.onload = () => window.turnstile?.render(el, { sitekey: TURNSTILE_SITE_KEY, callback: setTurnstileToken });
    document.head.appendChild(script);
  }, []);

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setState("sending");
    const res = await fetch("/v1/waitlist", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        email: form.get("email"),
        country: quote?.country ?? "XX",
        locale,
        useCase: form.get("useCase") || null,
        sourceChannel: new URLSearchParams(window.location.search).get("utm_source"),
        priceVariant: quote?.variant ?? "A",
        consentToContact: form.get("consent") === "on",
        confirmsAdult: form.get("adult") === "on",
        consentTextVersion: CONSENT_TEXT_VERSION,
        turnstileToken: turnstileToken || null,
      }),
    }).catch(() => null);
    setState(res && res.status === 202 ? "done" : "error");
  }

  return (
    <>
      <section className="prices" aria-live="polite">
        <h2>{t.pricesHeading}</h2>
        {quote && !quote.paidPlansAvailable && <p>{t.noPaidPlans}</p>}
        {quote && quote.paidPlansAvailable && quote.currency && (
          <>
            <div className="plans">
              {quote.plans.map((p) => (
                <div className="plan" key={p.plan}>
                  <h3>{p.plan === "creator" ? t.creator : t.pro}</h3>
                  <p className="amount">
                    {formatPrice(p.monthly, quote.currency!, locale)}
                    <span>{t.perMonth}</span>
                  </p>
                  <p className="tax">{p.includesTax ? t.inclGst : t.plusTax}</p>
                </div>
              ))}
            </div>
            <p className="note">{t.priceNote}</p>
          </>
        )}
      </section>

      <section className="signup">
        <h2>{t.formHeading}</h2>
        {state === "done" ? (
          <p role="status">{t.thanks}</p>
        ) : (
          <form onSubmit={submit}>
            <label>
              {t.email}
              <input name="email" type="email" required maxLength={254} autoComplete="email" />
            </label>
            <label>
              {t.useCase}
              <select name="useCase" defaultValue="">
                <option value="" disabled>
                  —
                </option>
                {t.useCases.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="check">
              <input name="consent" type="checkbox" required />
              <span>{t.consent}</span>
            </label>
            <label className="check">
              <input name="adult" type="checkbox" required />
              <span>{t.adult}</span>
            </label>
            {TURNSTILE_SITE_KEY && <div ref={turnstileRef} />}
            <button type="submit" disabled={state === "sending"}>
              {state === "sending" ? t.sending : t.submit}
            </button>
            {state === "error" && <p role="alert">{t.error}</p>}
          </form>
        )}
        <p className="privacy">
          {t.privacy}
          {CONTACT_EMAIL && (
            <>
              {" "}
              <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
            </>
          )}
        </p>
      </section>
    </>
  );
}
