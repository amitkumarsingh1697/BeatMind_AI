"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import RequireAccount from "@/components/Account";
import { checkoutResult, liveSubscription, openPaddle, openRazorpay, testOutcomes, type BillingSummary, type CheckoutStart } from "@/lib/billing";
import { problem } from "@/lib/format";
import { appText, errorText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import { formatPrice, pickVariant, type PricingQuote } from "@/lib/pricing";
import { useMe, type Me } from "@/lib/session";

const VARIANT_KEY = "bm_price_variant";

function storedVariant(): "A" | "B" {
  try {
    const v = pickVariant(localStorage.getItem(VARIANT_KEY));
    localStorage.setItem(VARIANT_KEY, v);
    return v;
  } catch {
    return pickVariant(null);
  }
}

/**
 * Plans by billing country (US-033): the person confirms the country of their billing address and
 * the price follows it, not where they browse from. Choosing a plan starts a Razorpay subscription
 * in India (US-034) or a Paddle checkout everywhere else (US-035); on a server without provider
 * keys a test dialog plays the payment through the real webhook handler instead.
 */
export default function Pricing({ locale }: { locale: Locale }) {
  const t = appText(locale);
  const B = t.billing;
  const { me } = useMe();
  const [country, setCountry] = useState<string | null>(null);
  const [variant, setVariant] = useState<"A" | "B">("A");
  const [quote, setQuote] = useState<PricingQuote | null>(null);
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [chosen, setChosen] = useState<"creator" | "pro" | null>(null);

  const loadSummary = useCallback(async () => {
    const r = await fetch("/v1/billing", { cache: "no-store" });
    const s: BillingSummary | null = r.ok ? await r.json() : null;
    setSummary(s);
    return s;
  }, []);

  useEffect(() => {
    setVariant(storedVariant());
  }, []);

  // The starting country: the one on the account, else the one Cloudflare sees.
  useEffect(() => {
    if (me === undefined || country) return;
    (async () => {
      const s = me ? await loadSummary() : null;
      let start = s?.billingCountry ?? "";
      if (!start) {
        const r = await fetch(`/v1/pricing?variant=${variant}`);
        start = r.ok ? ((await r.json()) as PricingQuote).country : "";
      }
      // XX is Cloudflare's "unknown"; India is the launch market. A choice made meanwhile wins.
      setCountry((chosen) => chosen ?? (start && start !== "XX" ? start : "IN"));
    })();
  }, [me, country, variant, loadSummary]);

  useEffect(() => {
    if (!country) return;
    fetch(`/v1/pricing?${new URLSearchParams({ country, variant })}`)
      .then((r) => (r.ok ? r.json() : null))
      .then(setQuote)
      .catch(() => setQuote(null));
  }, [country, variant]);

  const countries = { ...B.countries };
  if (country && !countries[country]) countries[country] = country;
  const live = liveSubscription(summary);
  const currentPlan = live?.plan ?? me?.plan ?? "free";

  return (
    <div className="stack-lg">
      <div className="field" style={{ maxInlineSize: 360 }}>
        <label htmlFor="billing-country">{B.country}</label>
        <select
          id="billing-country"
          className="select"
          value={country ?? ""}
          onChange={(e) => {
            setCountry(e.target.value);
            setChosen(null);
          }}
          aria-describedby="billing-country-hint"
        >
          {Object.entries(countries).map(([code, name]) => (
            <option key={code} value={code}>
              {name}
            </option>
          ))}
        </select>
        <p className="hint" id="billing-country-hint">
          {B.countryHint}
        </p>
      </div>

      <div className="plans" aria-live="polite">
        <div className="plan stack" style={{ alignContent: "start" }}>
          <h2 style={{ margin: 0, fontSize: "var(--step-1)" }}>{B.free}</h2>
          <p className="amount">
            {quote?.currency ? formatPrice(0, quote.currency, locale) : B.freePrice} <span>{B.perMonth}</span>
          </p>
          <ul className="small" style={{ margin: 0, paddingInlineStart: "1.2em" }}>
            {B.features.free.map((f) => (
              <li key={f}>{f}</li>
            ))}
          </ul>
          {me && currentPlan === "free" && <span className="badge">{B.current}</span>}
        </div>
        {(["creator", "pro"] as const).map((plan) => {
          const price = quote?.plans.find((p) => p.plan === plan);
          return (
            <div className="plan stack" key={plan} style={{ alignContent: "start" }}>
              <h2 style={{ margin: 0, fontSize: "var(--step-1)" }}>{t.plans[plan]}</h2>
              {quote && price && quote.currency ? (
                <>
                  <p className="amount">
                    {formatPrice(price.monthly, quote.currency, locale)} <span>{B.perMonth}</span>
                  </p>
                  <p className="small muted" style={{ margin: 0 }}>
                    {price.includesTax ? B.inclTax : B.plusTax}
                  </p>
                </>
              ) : (
                <p className="amount muted">…</p>
              )}
              <ul className="small" style={{ margin: 0, paddingInlineStart: "1.2em" }}>
                {B.features[plan].map((f) => (
                  <li key={f}>{f}</li>
                ))}
              </ul>
              {me && currentPlan === plan ? (
                <span className="badge badge-success">{B.current}</span>
              ) : (
                <button
                  type="button"
                  className={plan === "creator" ? "btn btn-primary" : "btn btn-secondary"}
                  disabled={!quote?.paidPlansAvailable || !!live}
                  aria-pressed={chosen === plan}
                  onClick={() => setChosen(plan)}
                >
                  {B.choose(t.plans[plan])}
                </button>
              )}
            </div>
          );
        })}
      </div>

      {quote && !quote.paidPlansAvailable && (
        <div className="notice" role="status">
          {B.internationalSoon}
        </div>
      )}
      <p className="small muted" style={{ margin: 0 }}>
        {country === "IN" ? B.payNote : B.paddleNote} {B.ownershipNote}
      </p>

      {chosen && country && quote?.paidPlansAvailable && (
        <RequireAccount locale={locale}>
          {(account) => (
            <Checkout
              key={chosen}
              me={account}
              plan={chosen}
              country={country}
              variant={quote?.variant ?? variant}
              locale={locale}
              onDone={loadSummary}
            />
          )}
        </RequireAccount>
      )}
    </div>
  );
}

function Checkout({
  me,
  plan,
  country,
  variant,
  locale,
  onDone,
}: {
  me: Me;
  plan: "creator" | "pro";
  country: string;
  variant: string;
  locale: Locale;
  onDone: () => unknown;
}) {
  const t = appText(locale);
  const B = t.billing;
  const dialog = useRef<HTMLDialogElement>(null);
  const [start, setStart] = useState<CheckoutStart | null>(null);
  const [outcome, setOutcome] = useState(country === "IN" ? "charged" : "card");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState("");

  async function begin() {
    setBusy(true);
    setError("");
    setResult("");
    const r = await fetch("/v1/checkout", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ plan, billingCountry: country, variant }),
    });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    const s: CheckoutStart = await r.json();
    setStart(s);
    if (s.mode === "stub") return dialog.current?.showModal();
    if (s.provider === "paddle") {
      try {
        await openPaddle(s, me.email, locale, () => {
          setResult(B.pendingPaid);
          onDone();
        });
      } catch {
        setError(t.errors.not_available);
      }
      return;
    }
    try {
      await openRazorpay({
        key: s.keyId ?? "",
        subscription_id: s.providerSubscriptionId,
        name: "BeatMind AI",
        description: t.plans[plan],
        prefill: { email: me.email },
        handler: () => {
          setResult(B.pendingPaid);
          onDone();
        },
      });
    } catch {
      if (s.shortUrl) window.location.href = s.shortUrl;
      else setError(t.errors.not_available);
    }
  }

  async function pay() {
    setBusy(true);
    setError("");
    const r = await fetch("/v1/billing/simulate", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(testOutcomes(start?.provider ?? "razorpay")[outcome]),
    });
    setBusy(false);
    if (!r.ok) return setError(errorText(locale, await problem(r)));
    const { outcomes } = (await r.json()) as { outcomes: string[] };
    dialog.current?.close();
    const kind = checkoutResult(outcomes);
    setResult(kind === "paid" ? B.paid(t.plans[plan]) : kind === "mismatch" ? B.mismatch : B.pendingPaid);
    onDone();
  }

  return (
    <section className="card flat stack" aria-labelledby="checkout-heading">
      <h2 id="checkout-heading" style={{ margin: 0 }}>
        {t.plans[plan]}
        {start && ` · ${formatPrice(start.monthly, start.currency, locale)} ${B.perMonth}`}
      </h2>
      <p className="small" style={{ margin: 0 }}>
        {country === "IN" ? B.payNote : B.paddleNote}
      </p>
      {!result && (
        <div>
          <button type="button" className="btn btn-primary" onClick={begin} disabled={busy}>
            {busy ? B.starting : B.pay}
          </button>
        </div>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {result && (
        <p className="notice" role="status">
          {result}
        </p>
      )}
      <dialog ref={dialog} aria-labelledby="test-pay-heading">
        <div className="stack">
          <h2 id="test-pay-heading">{B.testHeading}</h2>
          <p className="small">{B.testIntro}</p>
          <fieldset className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
            <legend className="field-label">{B.pay}</legend>
            {Object.keys(testOutcomes(start?.provider ?? "razorpay")).map((key) => (
              <label key={key} className="cluster" style={{ minBlockSize: "var(--tap)" }}>
                <input type="radio" name="test-outcome" value={key} checked={outcome === key} onChange={() => setOutcome(key)} />
                {B.testOutcomes[key]}
              </label>
            ))}
          </fieldset>
          <div className="cluster" style={{ justifyContent: "flex-end" }}>
            <button type="button" className="btn btn-ghost" onClick={() => dialog.current?.close()}>
              {t.common.cancel}
            </button>
            <button type="button" className="btn btn-primary" onClick={pay} disabled={busy}>
              {B.pay}
            </button>
          </div>
        </div>
      </dialog>
    </section>
  );
}
