package ai.beatmind.billing;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Published inside the transaction that records the payment change (REQ-F-57). */
public final class BillingEvents {

    private BillingEvents() {}

    /** The payment for a billing period settled: songs made in it on that subscription now vest. */
    public record PeriodSettled(UUID userId, UUID subscriptionId, UUID periodId, OffsetDateTime startsAt,
                                OffsetDateTime endsAt) {}

    /**
     * A settled period was reversed by a chargeback, a refund, or a payment from the wrong country:
     * songs made in it return to the free-plan rights state and lose their certificates.
     */
    public record PeriodReversed(UUID userId, UUID periodId, String reason) {}

    /** A subscription ended (cancelled at period end, halted or expired). Songs still vesting lapse. */
    public record SubscriptionEnded(UUID userId, UUID subscriptionId, String status) {}
}
