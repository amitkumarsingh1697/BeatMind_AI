// Mirrors the billing API (ai.beatmind.billing.Billing): GET /v1/billing, POST /v1/checkout.

export type Subscription = {
  id: string;
  plan: "creator" | "pro";
  status: "created" | "authenticated" | "active" | "pending" | "halted" | "cancelled" | "completed" | "expired";
  billingCountry: string;
  currency: "INR" | "USD";
  monthly: number;
  cancelAtPeriodEnd: boolean;
  currentStart: string | null;
  currentEnd: string | null;
  endedAt: string | null;
  createdAt: string;
};
export type Period = {
  id: string;
  plan: string;
  startsAt: string;
  endsAt: string;
  status: "settled" | "reversed";
  amount: number;
  currency: "INR" | "USD";
  paymentMethod: string | null;
  reversalReason: string | null;
};
export type BillingSummary = {
  plan: "free" | "creator" | "pro";
  billingCountry: string | null;
  mode: "stub" | "test" | "live";
  keyId: string | null;
  subscription: Subscription | null;
  periods: Period[];
};
export type CheckoutStart = {
  id: string;
  mode: "stub" | "test" | "live";
  keyId: string | null;
  providerSubscriptionId: string;
  shortUrl: string | null;
  plan: "creator" | "pro";
  currency: "INR" | "USD";
  monthly: number;
  includesTax: boolean;
  billingCountry: string;
};

const LIVE = ["authenticated", "active", "pending"];

/** The subscription that still gives the plan, if any (a "created" one has not been paid yet). */
export function liveSubscription(summary: BillingSummary | null): Subscription | null {
  const s = summary?.subscription;
  return s && LIVE.includes(s.status) ? s : null;
}

/** What the stub payment outcomes in the outcome list mean for the person (Billing.handle results). */
export function checkoutResult(outcomes: string[]): "paid" | "pending" | "mismatch" {
  if (outcomes.includes("billing_country_mismatch")) return "mismatch";
  if (outcomes.includes("settled")) return "paid";
  return "pending";
}

/** The test outcomes on the stub payment dialog, as (outcome, method) for POST /v1/billing/simulate. */
export const TEST_OUTCOMES: Record<string, { outcome: string; method: string }> = {
  charged: { outcome: "charged", method: "upi" },
  card: { outcome: "charged", method: "card" },
  foreign_card: { outcome: "foreign_card", method: "card" },
  pending: { outcome: "pending", method: "upi" },
};

type RazorpayOptions = {
  key: string;
  subscription_id: string;
  name: string;
  description: string;
  prefill?: { email?: string };
  theme?: { color?: string };
  handler: () => void;
  modal?: { ondismiss?: () => void };
};
declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => { open: () => void };
  }
}

/** Razorpay Checkout (test and live modes). Loaded only when someone pays. */
export function openRazorpay(options: RazorpayOptions): Promise<void> {
  return new Promise((resolve, reject) => {
    const open = () => {
      if (!window.Razorpay) return reject(new Error("Razorpay did not load"));
      new window.Razorpay(options).open();
      resolve();
    };
    if (window.Razorpay) return open();
    const script = document.createElement("script");
    script.src = "https://checkout.razorpay.com/v1/checkout.js";
    script.async = true;
    script.onload = open;
    script.onerror = () => reject(new Error("Razorpay did not load"));
    document.head.appendChild(script);
  });
}
