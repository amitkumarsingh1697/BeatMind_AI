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
  /** Razorpay in India, Paddle (merchant of record) everywhere else (ADR-009, US-035). */
  provider: "razorpay" | "paddle";
  mode: "stub" | "test" | "live";
  clientToken: string | null;
  priceId: string | null;
  environment: "sandbox" | "production" | null;
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

/** Outside India there is no UPI: card payments through the merchant of record (US-035). */
export const PADDLE_TEST_OUTCOMES: Record<string, { outcome: string; method: string }> = {
  card: { outcome: "charged", method: "card" },
  other_country: { outcome: "foreign_card", method: "card" },
  card_pending: { outcome: "pending", method: "card" },
};

export function testOutcomes(provider: CheckoutStart["provider"]) {
  return provider === "paddle" ? PADDLE_TEST_OUTCOMES : TEST_OUTCOMES;
}

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

type PaddleCheckout = {
  items: { priceId: string; quantity: number }[];
  customer?: { email?: string; address?: { countryCode: string } };
  customData?: Record<string, string>;
  settings?: { displayMode?: "overlay"; locale?: string; allowLogout?: boolean };
};
type PaddleJs = {
  Environment: { set: (env: "sandbox") => void };
  Initialize: (options: { token: string; eventCallback?: (e: { name: string }) => void }) => void;
  Checkout: { open: (options: PaddleCheckout) => void };
};
declare global {
  interface Window {
    Paddle?: PaddleJs;
  }
}

/**
 * Paddle checkout overlay (US-035). Paddle is the merchant of record: it shows the price for the
 * billing country with local tax added, takes the card and sends us signed notifications. Our
 * subscription id goes in custom data so the first notification finds it.
 */
export function openPaddle(start: CheckoutStart, email: string, locale: string, onComplete: () => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const open = () => {
      const paddle = window.Paddle;
      if (!paddle || !start.clientToken || !start.priceId) return reject(new Error("Paddle did not load"));
      if (start.environment !== "production") paddle.Environment.set("sandbox");
      paddle.Initialize({
        token: start.clientToken,
        eventCallback: (e) => {
          if (e.name === "checkout.completed") onComplete();
        },
      });
      paddle.Checkout.open({
        items: [{ priceId: start.priceId, quantity: 1 }],
        customer: { email, address: { countryCode: start.billingCountry } },
        customData: { bmSubscriptionId: start.id },
        settings: { displayMode: "overlay", locale, allowLogout: false },
      });
      resolve();
    };
    if (window.Paddle) return open();
    const script = document.createElement("script");
    script.src = "https://cdn.paddle.com/paddle/v2/paddle.js";
    script.async = true;
    script.onload = open;
    script.onerror = () => reject(new Error("Paddle did not load"));
    document.head.appendChild(script);
  });
}
