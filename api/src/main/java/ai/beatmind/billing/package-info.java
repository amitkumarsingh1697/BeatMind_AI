/**
 * Billing module (US-033, US-034, US-036, US-037, ADR-009): checkout with Razorpay in India, signed
 * webhooks processed once, paid billing periods, and cancellation. The billing country, not the
 * browsing country, decides the price (REQ-F-41).
 *
 * <p>Other modules use {@link ai.beatmind.billing.Billing} to read the ownership that a new song
 * gets, and listen for {@link ai.beatmind.billing.BillingEvents} to vest or reverse ownership of
 * songs when a payment settles, is reversed or a subscription ends (REQ-F-57).
 */
package ai.beatmind.billing;
