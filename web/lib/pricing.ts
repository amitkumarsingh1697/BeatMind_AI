// Mirrors the API-008 response (ai.beatmind.pricing.PricingQuote).
export type PlanPrice = { plan: "creator" | "pro"; monthly: number; includesTax: boolean };
export type PricingQuote = {
  country: string;
  band: string | null;
  bandIsDefault: boolean;
  paidPlansAvailable: boolean;
  currency: "INR" | "USD" | null;
  variant: "A" | "B";
  plans: PlanPrice[];
};

/** Formats a monthly price for display, e.g. "₹149" or "US$2.80". */
export function formatPrice(amount: number, currency: "INR" | "USD", locale: string): string {
  if (currency === "INR") {
    return "₹" + new Intl.NumberFormat(locale === "hi" ? "hi-IN" : "en-IN", { maximumFractionDigits: 0 }).format(amount);
  }
  return "US$" + amount.toFixed(2);
}

/** EXP-02 assigns each visitor one price variant at random, and keeps it for return visits. */
export function pickVariant(stored: string | null, random: () => number = Math.random): "A" | "B" {
  if (stored === "A" || stored === "B") return stored;
  return random() < 0.5 ? "A" : "B";
}
