package ai.beatmind.generation;

import ai.beatmind.billing.BillingEvents;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * US-036, REQ-F-57, BUG-043: what payments do to the rights in songs. Runs inside the webhook's
 * transaction, so a payment change and its effect on songs commit together.
 *
 * <ul>
 *   <li>Settled: songs made on the subscription while vesting, before the period's end, become the
 *       user's and are tied to that period.
 *   <li>Reversed (chargeback, refund, wrong-country card): songs tied to the period go back to the
 *       free-plan rights state, and their ownership certificates are revoked.
 *   <li>Subscription ended with songs still vesting: those songs lapse to the free-plan state.
 * </ul>
 */
@Component
class OwnershipVesting {

    private final JdbcClient jdbc;
    private final ProvenanceLedger ledger;

    OwnershipVesting(JdbcClient jdbc, ProvenanceLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @EventListener
    void settled(BillingEvents.PeriodSettled e) {
        List<UUID> vested = jdbc.sql("""
                UPDATE song SET rights_status = 'user_owned', billing_period_id = :period, updated_at = now()
                 WHERE owner_id = :user AND subscription_id = :sub AND rights_status = 'vesting' AND created_at < :end
                RETURNING id
                """).param("period", e.periodId()).param("user", e.userId()).param("sub", e.subscriptionId())
                .param("end", e.endsAt()).query(UUID.class).list();
        vested.forEach(id -> ledger.append(id, "ownership_vested", Actor.SYSTEM,
                Map.of("billingPeriodId", e.periodId().toString())));
    }

    @EventListener
    void reversed(BillingEvents.PeriodReversed e) {
        List<UUID> songs = jdbc.sql("""
                UPDATE song SET rights_status = 'bm_owned', updated_at = now()
                 WHERE billing_period_id = :period AND rights_status IN ('user_owned', 'vesting')
                RETURNING id
                """).param("period", e.periodId()).query(UUID.class).list();
        // A song removed after a report would be restored to the rights it had; it no longer has them.
        jdbc.sql("""
                UPDATE takedown_request SET previous_rights = 'bm_owned'
                 WHERE song_id IN (SELECT id FROM song WHERE billing_period_id = :period) AND previous_rights = 'user_owned'
                """).param("period", e.periodId()).update();
        for (UUID id : songs) {
            int revoked = jdbc.sql("""
                    UPDATE certificate SET revoked_at = now(), revoked_reason = 'payment_reversed'
                     WHERE song_id = :song AND scope = 'ownership_paid' AND revoked_at IS NULL
                    """).param("song", id).update();
            ledger.append(id, "ownership_reversed", Actor.SYSTEM, Map.of(
                    "billingPeriodId", e.periodId().toString(), "reason", e.reason(), "certificatesRevoked", revoked));
        }
    }

    @EventListener
    void ended(BillingEvents.SubscriptionEnded e) {
        List<UUID> lapsed = jdbc.sql("""
                UPDATE song SET rights_status = 'bm_owned', updated_at = now()
                 WHERE subscription_id = :sub AND rights_status = 'vesting'
                RETURNING id
                """).param("sub", e.subscriptionId()).query(UUID.class).list();
        lapsed.forEach(id -> ledger.append(id, "ownership_lapsed", Actor.SYSTEM,
                Map.of("reason", "payment_not_received", "subscriptionStatus", e.status())));
    }
}
