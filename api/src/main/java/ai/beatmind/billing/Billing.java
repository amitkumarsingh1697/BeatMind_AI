package ai.beatmind.billing;

import ai.beatmind.identity.Accounts;
import ai.beatmind.pricing.PricingQuote;
import ai.beatmind.pricing.PricingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plans, payments and the rights they buy (US-033, US-034, US-036, US-037).
 *
 * <p>The payment provider is the source of truth for money: Razorpay in India, Paddle as the
 * merchant of record everywhere else (ADR-009, US-035). Their signed webhooks are the only thing
 * that turns a plan on, settles a billing period or reverses one. Each webhook is processed once (REQ-SEC-08).
 * Ownership of a song made on a paid plan vests when the payment for that period settles; a
 * chargeback or full refund reverses the period (REQ-F-57). A captured payment counts as settled.
 */
@Service
public class Billing {

    private static final Logger log = LoggerFactory.getLogger(Billing.class);

    /** The rights a new song gets, and the billing period or subscription it belongs to. */
    public record Ownership(String rights, UUID periodId, UUID subscriptionId) {}

    public record Subscription(UUID id, String plan, String status, String billingCountry, String currency,
                               BigDecimal monthly, boolean cancelAtPeriodEnd, OffsetDateTime currentStart,
                               OffsetDateTime currentEnd, OffsetDateTime endedAt, OffsetDateTime createdAt) {}

    public record Period(UUID id, String plan, OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                         BigDecimal amount, String currency, String paymentMethod, String reversalReason) {}

    public record Summary(String plan, String billingCountry, String mode, String keyId, Subscription subscription,
                          List<Period> periods) {}

    /**
     * What the web app needs to open checkout: Razorpay Checkout (key and subscription), Paddle.js
     * (client token, price and our subscription id as custom data), or the stub dialog.
     */
    public record CheckoutStart(UUID id, String provider, String mode, String keyId, String providerSubscriptionId,
                                String shortUrl, String clientToken, String priceId, String environment,
                                String plan, String currency, BigDecimal monthly, boolean includesTax,
                                String billingCountry) {}

    /** A checkout or cancel the user cannot do, with a code and a sentence for them. */
    public static final class Refusal extends RuntimeException {
        public final int status;
        public final String code;

        Refusal(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    static final List<String> LIVE = List.of("created", "authenticated", "active", "pending");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PricingService pricing;
    private final Accounts accounts;
    private final RazorpayClient razorpay;
    private final PaddleClient paddle;
    private final BillingProperties props;
    private final ApplicationEventPublisher events;

    Billing(JdbcClient jdbc, JsonMapper json, PricingService pricing, Accounts accounts, RazorpayClient razorpay,
            PaddleClient paddle, BillingProperties props, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.pricing = pricing;
        this.accounts = accounts;
        this.razorpay = razorpay;
        this.paddle = paddle;
        this.props = props;
        this.events = events;
    }

    // ---- Song rights (REQ-F-22, REQ-F-57) ----

    /**
     * Rights for a song created now. Free plan: BeatMind owns it. Paid plan through a subscription:
     * the user owns it if the current period's payment has settled, otherwise it is vesting until it
     * does. A paid plan an admin gave without a subscription (beta testers) owns at once.
     */
    public Ownership ownershipAtCreation(UUID userId, String plan) {
        if (!"creator".equals(plan) && !"pro".equals(plan)) {
            return new Ownership("bm_owned", null, null);
        }
        Optional<UUID> sub = jdbc.sql("""
                SELECT id FROM subscription WHERE user_id = :user AND status IN ('authenticated', 'active', 'pending')
                """).param("user", userId).query(UUID.class).optional();
        if (sub.isEmpty()) {
            return new Ownership("user_owned", null, null);
        }
        Optional<UUID> period = jdbc.sql("""
                SELECT id FROM billing_period
                 WHERE subscription_id = :sub AND status = 'settled' AND starts_at <= now() AND ends_at > now()
                 ORDER BY starts_at DESC LIMIT 1
                """).param("sub", sub.get()).query(UUID.class).optional();
        return period.map(p -> new Ownership("user_owned", p, sub.get()))
                .orElseGet(() -> new Ownership("vesting", null, sub.get()));
    }

    // ---- Checkout (US-033, US-034) ----

    /**
     * Starts a subscription at the billing country's price (REQ-F-41): through Razorpay for India,
     * through Paddle for every other country with paid plans (REQ-F-42, US-035).
     */
    @Transactional
    public CheckoutStart checkout(UUID userId, String plan, String billingCountry, String variant) {
        if (!"creator".equals(plan) && !"pro".equals(plan)) {
            throw new Refusal(400, "invalid_plan", "Choose Creator or Pro.");
        }
        PricingQuote quote = pricing.quote(billingCountry, variant);
        if (!quote.paidPlansAvailable()) {
            throw new Refusal(409, "not_available", "Paid plans are not available in your country yet.");
        }
        boolean india = "IN".equals(quote.country());
        String priceId = india ? null : paddle.priceId(plan, quote.variant());
        if (!india && priceId == null) {
            throw new Refusal(409, "international_soon",
                    "Paying from outside India opens soon. You can keep making songs on the free plan.");
        }
        jdbc.sql("SELECT id FROM app_user WHERE id = :id FOR UPDATE").param("id", userId).query(UUID.class).single();
        Optional<Subscription> current = current(userId);
        if (current.isPresent()) {
            Subscription s = current.get();
            if (!"created".equals(s.status())) {
                throw new Refusal(409, "already_subscribed", "You already have a plan. Cancel it first to change plans.");
            }
            // An abandoned checkout: replace it.
            jdbc.sql("UPDATE subscription SET status = 'expired', ended_at = now(), updated_at = now() WHERE id = :id")
                    .param("id", s.id()).update();
        }
        PricingQuote.PlanPrice price = quote.plans().stream().filter(p -> p.plan().equals(plan)).findFirst().orElseThrow();
        UUID id = UUID.randomUUID();
        // Paddle makes its subscription when the person pays in the overlay; until its first
        // webhook names it, the row holds a placeholder and is found by the custom data we pass.
        RazorpayClient.Created created = india
                ? razorpay.createSubscription(razorpay.planId(plan, quote.variant()), userId, id)
                : new RazorpayClient.Created("pending_" + id, null);
        String provider = india ? "razorpay" : "paddle";
        jdbc.sql("""
                INSERT INTO subscription (id, user_id, plan, billing_country, price_band, variant, currency, amount_minor,
                                          provider, provider_subscription_id)
                VALUES (:id, :user, :plan, :country, :band, :variant, :currency, :amount, :providerName, :provider)
                """)
                .param("id", id).param("user", userId).param("plan", plan).param("country", quote.country())
                .param("band", quote.band() == null ? null : quote.band().name()).param("variant", quote.variant())
                .param("currency", quote.currency())
                .param("amount", price.monthly().movePointRight(2).longValueExact())
                .param("provider", created.subscriptionId()).param("providerName", provider)
                .update();
        accounts.setBillingCountry(userId, quote.country());
        return new CheckoutStart(id, provider, props.mode(), india ? props.razorpay().keyId() : null,
                india ? created.subscriptionId() : null, created.shortUrl(),
                india ? null : props.paddle().clientToken(), priceId, india ? null : paddle.environment(),
                plan, quote.currency(), price.monthly(), price.includesTax(), quote.country());
    }

    // ---- Cancellation (US-037, REQ-F-43, REQ-F-58) ----

    /**
     * Stops renewal. The plan stays until the end of the period already paid for, and owned songs
     * stay owned and downloadable after that.
     */
    @Transactional
    public Summary cancel(UUID userId) {
        Subscription s = current(userId).orElseThrow(() -> new Refusal(404, "no_plan", "You are on the free plan."));
        if ("created".equals(s.status())) {
            end(s.id(), userId, "cancelled");
            return summary(userId);
        }
        if (!s.cancelAtPeriodEnd()) {
            cancelAtProvider(s.id(), false);
            jdbc.sql("""
                    UPDATE subscription SET cancel_at_period_end = true, cancel_requested_at = now(), updated_at = now()
                     WHERE id = :id
                    """).param("id", s.id()).update();
        }
        return summary(userId);
    }

    /** Ends subscriptions whose cancellation date has passed, in case the provider's event is late. */
    @Scheduled(fixedDelayString = "${beatmind.billing.expire-every:10m}", initialDelayString = "30s")
    @Transactional
    public int expire() {
        record Due(UUID id, UUID userId) {}
        List<Due> due = jdbc.sql("""
                SELECT id, user_id FROM subscription
                 WHERE status IN ('authenticated', 'active', 'pending') AND cancel_at_period_end AND current_end <= now()
                 FOR UPDATE SKIP LOCKED
                """).query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class))).list();
        due.forEach(d -> end(d.id(), d.userId(), "cancelled"));
        return due.size();
    }

    /** Account deletion (US-011): stop any plan now, without a refund of the current period. */
    @Transactional
    public void closeAccount(UUID userId) {
        current(userId).ifPresent(s -> {
            if (!"created".equals(s.status())) {
                cancelAtProvider(s.id(), true);
            }
            end(s.id(), userId, "cancelled");
        });
    }

    // ---- Reading ----

    public Optional<Subscription> current(UUID userId) {
        return jdbc.sql(SUB_SELECT + " WHERE user_id = :user AND status IN ('created', 'authenticated', 'active', 'pending')")
                .param("user", userId).query(Billing::sub).optional();
    }

    public Summary summary(UUID userId) {
        Accounts.Profile p = accounts.profile(userId).orElseThrow();
        Subscription s = current(userId).orElseGet(() -> jdbc.sql(SUB_SELECT
                        + " WHERE user_id = :user AND status <> 'expired' ORDER BY created_at DESC LIMIT 1")
                .param("user", userId).query(Billing::sub).optional().orElse(null));
        List<Period> periods = jdbc.sql("""
                SELECT id, plan, starts_at, ends_at, status, amount_minor, currency, payment_method, reversal_reason
                  FROM billing_period WHERE user_id = :user ORDER BY starts_at DESC LIMIT 12
                """).param("user", userId)
                .query((rs, n) -> new Period(rs.getObject("id", UUID.class), rs.getString("plan"),
                        rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getString("status"), BigDecimal.valueOf(rs.getLong("amount_minor"), 2), rs.getString("currency"),
                        rs.getString("payment_method"), rs.getString("reversal_reason")))
                .list();
        return new Summary(p.plan(), accounts.billingCountry(userId).orElse(null), props.mode(),
                props.razorpay().keyId(), s, periods);
    }

    // ---- Webhooks (API-010, REQ-SEC-08) ----

    /** Paddle (US-035): the Paddle-Signature header is "ts=...;h1=...", an HMAC-SHA256 of "ts:body". */
    public boolean paddleSignatureValid(String body, String header) {
        return paddleSignatureValid(body, header, Instant.now());
    }

    boolean paddleSignatureValid(String body, String header, Instant now) {
        String secret = props.paddleWebhookSecret();
        if (secret.isEmpty() || header == null || body == null) {
            return false;
        }
        String ts = null;
        List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "ts".equals(kv[0])) {
                ts = kv[1].trim();
            } else if (kv.length == 2 && "h1".equals(kv[0])) {
                signatures.add(kv[1].trim().toLowerCase(Locale.ROOT));
            }
        }
        if (ts == null || signatures.isEmpty()) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        // A replayed old notification is refused even with a valid signature.
        if (Math.abs(now.getEpochSecond() - seconds) > PADDLE_TOLERANCE_SECONDS) {
            return false;
        }
        byte[] expected = hmac(secret, ts + ":" + body).getBytes(StandardCharsets.US_ASCII);
        return signatures.stream().anyMatch(sig -> MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII)));
    }

    static final long PADDLE_TOLERANCE_SECONDS = 300;

    /** Checks the X-Razorpay-Signature header: HMAC-SHA256 of the raw body with the webhook secret. */
    public boolean signatureValid(String body, String signature) {
        String secret = props.webhookSecret();
        if (secret.isEmpty() || signature == null || body == null) {
            return false;
        }
        byte[] expected = hmac(secret, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Processes one verified webhook. Returns what happened: "duplicate" when the event was seen
     * before, "ignored" for events we do not use, else the event's outcome.
     */
    @Transactional
    public String handle(String body, String eventId) {
        JsonNode event = json.readTree(body);
        String type = event.path("event").asString("");
        String id = eventId == null || eventId.isBlank() ? "sha:" + sha256(body) : eventId;
        int fresh = jdbc.sql("""
                INSERT INTO payment_webhook (provider, event_id, event_type, body_sha256, outcome)
                VALUES ('razorpay', :id, :type, :sha, 'processing') ON CONFLICT DO NOTHING
                """).param("id", id).param("type", type).param("sha", sha256(body)).update();
        if (fresh == 0) {
            return "duplicate";
        }
        JsonNode payload = event.path("payload");
        String outcome = switch (type) {
            case "subscription.authenticated", "subscription.activated" -> status(payload, type.substring(13));
            case "subscription.charged" -> charged(payload);
            case "subscription.pending" -> status(payload, "pending");
            case "subscription.halted", "subscription.cancelled", "subscription.completed" -> ended(payload, type.substring(13));
            case "payment.dispute.lost" -> reverseByPayment(payload.path("payment").path("entity").path("id").asString(""), "chargeback");
            case "refund.processed" -> refunded(payload.path("refund").path("entity"));
            default -> "ignored";
        };
        jdbc.sql("UPDATE payment_webhook SET outcome = :o WHERE provider = 'razorpay' AND event_id = :id")
                .param("o", outcome).param("id", id).update();
        log.info("razorpay webhook {} {}: {}", id, type, outcome);
        return outcome;
    }

    private record Sub(UUID id, UUID userId, String plan, String billingCountry, long amountMinor, String currency) {}

    private Optional<Sub> byProvider(String providerId) {
        return jdbc.sql("""
                SELECT id, user_id, plan, billing_country, amount_minor, currency FROM subscription
                 WHERE provider_subscription_id = :p FOR UPDATE
                """).param("p", providerId)
                .query((rs, n) -> new Sub(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("plan"), rs.getString("billing_country"), rs.getLong("amount_minor"),
                        rs.getString("currency")))
                .optional();
    }

    private String status(JsonNode payload, String status) {
        JsonNode entity = payload.path("subscription").path("entity");
        Optional<Sub> sub = byProvider(entity.path("id").asString(""));
        if (sub.isEmpty()) {
            return "unknown_subscription";
        }
        jdbc.sql("""
                UPDATE subscription SET status = :status, current_start = coalesce(:start, current_start),
                       current_end = coalesce(:end, current_end), updated_at = now()
                 WHERE id = :id AND status IN ('created', 'authenticated', 'active', 'pending')
                """).param("status", status).param("start", time(entity.path("current_start")))
                .param("end", time(entity.path("current_end"))).param("id", sub.get().id()).update();
        return status;
    }

    private String charged(JsonNode payload) {
        JsonNode subEntity = payload.path("subscription").path("entity");
        JsonNode payment = payload.path("payment").path("entity");
        Optional<Sub> found = byProvider(subEntity.path("id").asString(""));
        if (found.isEmpty()) {
            return "unknown_subscription";
        }
        Sub sub = found.get();
        if (!"captured".equals(payment.path("status").asString(""))) {
            return "not_captured";
        }
        String paymentId = payment.path("id").asString("");
        OffsetDateTime start = time(subEntity.path("current_start"));
        OffsetDateTime end = time(subEntity.path("current_end"));
        if (start == null || end == null) {
            return "missing_period";
        }
        String country = payment.path("card").path("country").asString("");
        boolean mismatch = payment.path("international").asBoolean(false)
                || (!country.isBlank() && !country.equalsIgnoreCase(sub.billingCountry()));
        UUID periodId = insertPeriod(sub, paymentId, start, end, payment.path("amount").asLong(sub.amountMinor()),
                payment.path("currency").asString(sub.currency()), payment.path("method").asString(null), mismatch);
        if (periodId == null) {
            return "duplicate_payment";
        }
        if (mismatch) {
            // REQ-F-41: the payment method's country is the billing country. A foreign card on an
            // Indian price is refunded and the subscription stopped; the user can pay again from
            // the right country's checkout.
            razorpay.refund(paymentId);
            razorpay.cancelNow(subEntity.path("id").asString(""));
            end(sub.id(), sub.userId(), "cancelled");
            return "billing_country_mismatch";
        }
        return activate(sub, periodId, start, end);
    }

    /** One paid period; null when the payment was seen before. */
    private UUID insertPeriod(Sub sub, String paymentId, OffsetDateTime start, OffsetDateTime end, long amount,
                              String currency, String method, boolean mismatch) {
        UUID periodId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO billing_period (id, subscription_id, user_id, plan, starts_at, ends_at, provider_payment_id,
                                            amount_minor, currency, payment_method, status, settled_at, reversed_at,
                                            reversal_reason)
                VALUES (:id, :sub, :user, :plan, :start, :end, :payment, :amount, :currency, :method, :status,
                        CASE WHEN :mismatch THEN NULL ELSE now() END, CASE WHEN :mismatch THEN now() END,
                        CASE WHEN :mismatch THEN 'billing_country_mismatch' END)
                ON CONFLICT (provider_payment_id) DO NOTHING
                """)
                .param("id", periodId).param("sub", sub.id()).param("user", sub.userId()).param("plan", sub.plan())
                .param("start", start).param("end", end).param("payment", paymentId)
                .param("amount", amount).param("currency", currency)
                .param("method", method).param("status", mismatch ? "reversed" : "settled")
                .param("mismatch", mismatch)
                .update();
        return inserted == 0 ? null : periodId;
    }

    /** The plan is on for the paid period, and songs made in it vest (REQ-F-57). */
    private String activate(Sub sub, UUID periodId, OffsetDateTime start, OffsetDateTime end) {
        jdbc.sql("""
                UPDATE subscription SET status = 'active', current_start = :start, current_end = :end, updated_at = now()
                 WHERE id = :id
                """).param("start", start).param("end", end).param("id", sub.id()).update();
        accounts.setPlan(sub.userId(), sub.plan());
        events.publishEvent(new BillingEvents.PeriodSettled(sub.userId(), sub.id(), periodId, start, end));
        return "settled";
    }

    private String ended(JsonNode payload, String status) {
        Optional<Sub> sub = byProvider(payload.path("subscription").path("entity").path("id").asString(""));
        if (sub.isEmpty()) {
            return "unknown_subscription";
        }
        end(sub.get().id(), sub.get().userId(), status);
        return status;
    }

    private String refunded(JsonNode refund) {
        String paymentId = refund.path("payment_id").asString("");
        Long amount = jdbc.sql("SELECT amount_minor FROM billing_period WHERE provider_payment_id = :p AND status = 'settled'")
                .param("p", paymentId).query(Long.class).optional().orElse(null);
        if (amount == null) {
            return "unknown_payment";
        }
        if (refund.path("amount").asLong(0) < amount) {
            return "partial_refund";
        }
        return reverseByPayment(paymentId, "refund");
    }

    /** REQ-F-57, BUG-043: the songs made in the period go back to the free-plan rights state. */
    private String reverseByPayment(String paymentId, String reason) {
        record P(UUID id, UUID userId, UUID subscriptionId, OffsetDateTime endsAt) {}
        Optional<P> period = jdbc.sql("""
                SELECT id, user_id, subscription_id, ends_at FROM billing_period
                 WHERE provider_payment_id = :p AND status = 'settled' FOR UPDATE
                """).param("p", paymentId)
                .query((rs, n) -> new P(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("subscription_id", UUID.class), rs.getObject("ends_at", OffsetDateTime.class)))
                .optional();
        if (period.isEmpty()) {
            return "unknown_payment";
        }
        P p = period.get();
        jdbc.sql("""
                UPDATE billing_period SET status = 'reversed', reversed_at = now(), reversal_reason = :reason WHERE id = :id
                """).param("reason", reason).param("id", p.id()).update();
        events.publishEvent(new BillingEvents.PeriodReversed(p.userId(), p.id(), reason));
        if (p.endsAt().isAfter(OffsetDateTime.now())) {
            // The money for the current period is gone, so the plan stops now.
            cancelAtProvider(p.subscriptionId(), true);
            end(p.subscriptionId(), p.userId(), "cancelled");
        }
        return "reversed";
    }

    private void end(UUID subscriptionId, UUID userId, String status) {
        int changed = jdbc.sql("""
                UPDATE subscription SET status = :status, ended_at = now(), updated_at = now()
                 WHERE id = :id AND status IN ('created', 'authenticated', 'active', 'pending')
                """).param("status", status).param("id", subscriptionId).update();
        if (changed == 1) {
            accounts.setPlan(userId, "free");
            events.publishEvent(new BillingEvents.SubscriptionEnded(userId, subscriptionId, status));
        }
    }

    private String providerId(UUID subscriptionId) {
        return jdbc.sql("SELECT provider_subscription_id FROM subscription WHERE id = :id")
                .param("id", subscriptionId).query(String.class).single();
    }

    private String providerOf(UUID subscriptionId) {
        return jdbc.sql("SELECT provider FROM subscription WHERE id = :id")
                .param("id", subscriptionId).query(String.class).single();
    }

    private void cancelAtProvider(UUID subscriptionId, boolean immediately) {
        String providerSub = providerId(subscriptionId);
        if ("paddle".equals(providerOf(subscriptionId))) {
            paddle.cancel(providerSub, immediately);
        } else if (immediately) {
            razorpay.cancelNow(providerSub);
        } else {
            razorpay.cancelAtCycleEnd(providerSub);
        }
    }

    // ---- Paddle webhooks (US-035, ADR-009, REQ-SEC-08) ----

    /**
     * Processes one verified Paddle notification, once per event_id. Paddle sends the subscription
     * and its transactions separately: a completed transaction settles a period; an approved
     * chargeback, or a full refund, reverses it.
     */
    @Transactional
    public String handlePaddle(String body) {
        JsonNode event = json.readTree(body);
        String type = event.path("event_type").asString("");
        String id = event.path("event_id").asString("");
        if (id.isBlank()) {
            id = "sha:" + sha256(body);
        }
        int fresh = jdbc.sql("""
                INSERT INTO payment_webhook (provider, event_id, event_type, body_sha256, outcome)
                VALUES ('paddle', :id, :type, :sha, 'processing') ON CONFLICT DO NOTHING
                """).param("id", id).param("type", type).param("sha", sha256(body)).update();
        if (fresh == 0) {
            return "duplicate";
        }
        JsonNode data = event.path("data");
        String outcome = switch (type) {
            case "subscription.created", "subscription.activated", "subscription.updated", "subscription.resumed",
                 "subscription.past_due", "subscription.paused", "subscription.canceled" -> paddleSubscription(data);
            case "transaction.completed" -> paddleTransaction(data);
            case "adjustment.created", "adjustment.updated" -> paddleAdjustment(data);
            default -> "ignored";
        };
        jdbc.sql("UPDATE payment_webhook SET outcome = :o WHERE provider = 'paddle' AND event_id = :id")
                .param("o", outcome).param("id", id).update();
        log.info("paddle webhook {} {}: {}", id, type, outcome);
        return outcome;
    }

    /**
     * Finds our subscription by Paddle's id or, on the first notification, by the id we put in the
     * checkout's custom data, and records Paddle's id on it.
     */
    private Optional<Sub> paddleSub(String paddleSubscriptionId, JsonNode customData) {
        if (!paddleSubscriptionId.isBlank()) {
            Optional<Sub> known = byProvider(paddleSubscriptionId);
            if (known.isPresent()) {
                return known;
            }
        }
        UUID ours;
        try {
            ours = UUID.fromString(customData.path("bmSubscriptionId").asString(""));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String placeholder = "pending_" + ours;
        Optional<Sub> pending = byProvider(placeholder);
        if (pending.isPresent() && !paddleSubscriptionId.isBlank()) {
            jdbc.sql("UPDATE subscription SET provider_subscription_id = :p, updated_at = now() WHERE id = :id AND provider = 'paddle'")
                    .param("p", paddleSubscriptionId).param("id", ours).update();
        }
        return pending;
    }

    private String paddleSubscription(JsonNode data) {
        Optional<Sub> found = paddleSub(data.path("id").asString(""), data.path("custom_data"));
        if (found.isEmpty()) {
            return "unknown_subscription";
        }
        Sub sub = found.get();
        String status = data.path("status").asString("");
        switch (status) {
            case "canceled" -> {
                end(sub.id(), sub.userId(), "cancelled");
                return "cancelled";
            }
            case "paused" -> {
                end(sub.id(), sub.userId(), "halted");
                return "halted";
            }
            default -> {
                // active and trialing keep the plan; past_due is a failed renewal Paddle retries.
                String ours = "past_due".equals(status) ? "pending" : "active".equals(status) || "trialing".equals(status)
                        ? "authenticated" : null;
                if (ours == null) {
                    return "ignored";
                }
                JsonNode period = data.path("current_billing_period");
                boolean cancelling = "cancel".equals(data.path("scheduled_change").path("action").asString(""));
                jdbc.sql("""
                        UPDATE subscription
                           SET status = CASE WHEN status = 'active' AND :status = 'authenticated' THEN status ELSE :status END,
                               current_start = coalesce(:start, current_start), current_end = coalesce(:end, current_end),
                               cancel_at_period_end = cancel_at_period_end OR :cancelling, updated_at = now()
                         WHERE id = :id AND status IN ('created', 'authenticated', 'active', 'pending')
                        """).param("status", ours).param("start", iso(period.path("starts_at")))
                        .param("end", iso(period.path("ends_at"))).param("cancelling", cancelling)
                        .param("id", sub.id()).update();
                return ours;
            }
        }
    }

    private String paddleTransaction(JsonNode data) {
        Optional<Sub> found = paddleSub(data.path("subscription_id").asString(""), data.path("custom_data"));
        if (found.isEmpty()) {
            return "unknown_subscription";
        }
        Sub sub = found.get();
        if (!"completed".equals(data.path("status").asString("completed"))) {
            return "not_captured";
        }
        JsonNode period = data.path("billing_period");
        OffsetDateTime start = iso(period.path("starts_at"));
        OffsetDateTime end = iso(period.path("ends_at"));
        if (start == null || end == null) {
            return "missing_period";
        }
        // REQ-F-41: Paddle prices by the customer's address, so the address country is the billing
        // country. If the person changed it in the overlay, the account follows what they paid.
        String country = data.path("address").path("country_code").asString("").toUpperCase(Locale.ROOT);
        if (country.matches("[A-Z]{2}") && !country.equals(sub.billingCountry())) {
            jdbc.sql("UPDATE subscription SET billing_country = :c, updated_at = now() WHERE id = :id")
                    .param("c", country).param("id", sub.id()).update();
            accounts.setBillingCountry(sub.userId(), country);
        }
        JsonNode totals = data.path("details").path("totals");
        long amount = parseLong(totals.path("grand_total").asString(""), sub.amountMinor());
        String currency = data.path("currency_code").asString(sub.currency());
        String method = data.path("payments").path(0).path("method_details").path("type").asString(null);
        UUID periodId = insertPeriod(sub, data.path("id").asString(""), start, end, amount, currency, method, false);
        if (periodId == null) {
            return "duplicate_payment";
        }
        return activate(sub, periodId, start, end);
    }

    private String paddleAdjustment(JsonNode data) {
        if (!"approved".equals(data.path("status").asString(""))) {
            return "adjustment_" + data.path("status").asString("pending");
        }
        String transaction = data.path("transaction_id").asString("");
        String action = data.path("action").asString("");
        if ("chargeback".equals(action)) {
            return reverseByPayment(transaction, "chargeback");
        }
        if (!"refund".equals(action)) {
            return "ignored";
        }
        if ("full".equals(data.path("type").asString(""))) {
            return reverseByPayment(transaction, "refund");
        }
        Long amount = jdbc.sql("SELECT amount_minor FROM billing_period WHERE provider_payment_id = :p AND status = 'settled'")
                .param("p", transaction).query(Long.class).optional().orElse(null);
        if (amount == null) {
            return "unknown_payment";
        }
        long refunded = parseLong(data.path("totals").path("total").asString(""), 0);
        return refunded >= amount ? reverseByPayment(transaction, "refund") : "partial_refund";
    }

    // ---- Stub mode: simulated Razorpay events through the real handler ----

    /**
     * Local development and tests only: makes the events Razorpay would send for an outcome, signs
     * them with the webhook secret and runs them through {@link #handle}. Outcomes: charged (first
     * payment), renew, pending, halted, dispute_lost, refund, foreign_card.
     */
    public List<String> simulate(UUID userId, String outcome, String method) {
        if (!props.stub()) {
            throw new Refusal(404, "not_found", "Not found.");
        }
        record S(UUID id, String provider, long amount, OffsetDateTime start, OffsetDateTime end, String name,
                 String country, String currency) {}
        S s = jdbc.sql("""
                SELECT id, provider_subscription_id, amount_minor, current_start, current_end, provider, billing_country,
                       currency
                  FROM subscription WHERE user_id = :user ORDER BY created_at DESC LIMIT 1
                """).param("user", userId)
                .query((rs, n) -> new S(rs.getObject("id", UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class), rs.getString(6),
                        rs.getString(7), rs.getString(8)))
                .optional().orElseThrow(() -> new Refusal(404, "no_plan", "Start a checkout first."));
        if ("paddle".equals(s.name())) {
            return simulatePaddle(s.id(), s.provider(), s.amount(), s.end(), s.country(), s.currency(), outcome, method);
        }
        String payMethod = method == null || method.isBlank() ? "upi" : method;
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        long now = Instant.now().getEpochSecond();
        switch (outcome) {
            case "charged", "foreign_card" -> {
                long start = now;
                long end = OffsetDateTime.now(ZoneOffset.UTC).plusMonths(1).toEpochSecond();
                out.add(subEvent("subscription.authenticated", s.provider(), "authenticated", start, end));
                out.add(chargedEvent(s.provider(), start, end, s.amount(), payMethod, "foreign_card".equals(outcome)));
            }
            case "renew" -> {
                long start = s.end() == null ? now : s.end().toEpochSecond();
                long end = Instant.ofEpochSecond(start).atOffset(ZoneOffset.UTC).plusMonths(1).toEpochSecond();
                out.add(chargedEvent(s.provider(), start, end, s.amount(), payMethod, false));
            }
            case "pending", "halted" -> out.add(subEvent("subscription." + outcome, s.provider(), outcome, 0, 0));
            case "dispute_lost", "refund" -> {
                String payment = jdbc.sql("""
                        SELECT provider_payment_id FROM billing_period WHERE subscription_id = :sub AND status = 'settled'
                         ORDER BY starts_at DESC LIMIT 1
                        """).param("sub", s.id()).query(String.class).optional()
                        .orElseThrow(() -> new Refusal(409, "no_payment", "There is no settled payment to reverse."));
                Map<String, Object> e = new LinkedHashMap<>();
                if ("refund".equals(outcome)) {
                    e.put("event", "refund.processed");
                    e.put("payload", Map.of("refund", Map.of("entity",
                            Map.of("id", "rfnd_stub_" + now, "payment_id", payment, "amount", s.amount()))));
                } else {
                    e.put("event", "payment.dispute.lost");
                    e.put("payload", Map.of("payment", Map.of("entity", Map.of("id", payment)),
                            "dispute", Map.of("entity", Map.of("id", "disp_stub_" + now))));
                }
                out.add(e);
            }
            default -> throw new Refusal(400, "invalid_outcome", "Unknown outcome.");
        }
        return out.stream().map(e -> {
            String body = json.writeValueAsString(e);
            if (!signatureValid(body, hmac(props.webhookSecret(), body))) {
                throw new IllegalStateException("stub signature");
            }
            return handle(body, "evt_stub_" + UUID.randomUUID());
        }).toList();
    }

    private static Map<String, Object> subEvent(String type, String provider, String status, long start, long end) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", provider);
        entity.put("status", status);
        if (start > 0) {
            entity.put("current_start", start);
            entity.put("current_end", end);
        }
        return Map.of("entity", "event", "event", type, "payload", Map.of("subscription", Map.of("entity", entity)));
    }

    private static Map<String, Object> chargedEvent(String provider, long start, long end, long amount, String method,
                                                    boolean foreign) {
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", "pay_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        payment.put("amount", amount);
        payment.put("currency", "INR");
        payment.put("status", "captured");
        payment.put("method", foreign ? "card" : method);
        payment.put("international", foreign);
        if (foreign) {
            payment.put("card", Map.of("country", "US", "network", "Visa"));
        }
        return Map.of("entity", "event", "event", "subscription.charged", "payload", Map.of(
                "subscription", Map.of("entity", Map.of("id", provider, "status", "active",
                        "current_start", start, "current_end", end)),
                "payment", Map.of("entity", payment)));
    }

    /** Stub mode: the notifications Paddle would send for an outcome, signed and handled. */
    private List<String> simulatePaddle(UUID ours, String providerSub, long amount, OffsetDateTime currentEnd,
                                        String country, String currency, String outcome, String method) {
        String paddleSub = providerSub.startsWith("pending_")
                ? "sub_stub_" + ours.toString().replace("-", "").substring(0, 14) : providerSub;
        Map<String, Object> custom = Map.of("bmSubscriptionId", ours.toString());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        switch (outcome) {
            case "charged", "foreign_card" -> {
                OffsetDateTime end = now.plusMonths(1);
                out.add(paddleEvent("subscription.created", paddleSubscriptionData(paddleSub, "active", now, end, custom)));
                out.add(paddleEvent("transaction.completed", paddleTransactionData(paddleSub, custom, now, end, amount,
                        currency, "foreign_card".equals(outcome) ? "DE" : country, method)));
            }
            case "renew" -> {
                OffsetDateTime start = currentEnd == null ? now : currentEnd;
                out.add(paddleEvent("transaction.completed", paddleTransactionData(paddleSub, custom, start,
                        start.plusMonths(1), amount, currency, country, method)));
            }
            case "pending" -> out.add(paddleEvent("subscription.past_due",
                    paddleSubscriptionData(paddleSub, "past_due", null, null, custom)));
            case "halted" -> out.add(paddleEvent("subscription.paused",
                    paddleSubscriptionData(paddleSub, "paused", null, null, custom)));
            case "dispute_lost", "refund" -> {
                String txn = jdbc.sql("""
                        SELECT provider_payment_id FROM billing_period WHERE subscription_id = :sub AND status = 'settled'
                         ORDER BY starts_at DESC LIMIT 1
                        """).param("sub", ours).query(String.class).optional()
                        .orElseThrow(() -> new Refusal(409, "no_payment", "There is no settled payment to reverse."));
                Map<String, Object> adj = new LinkedHashMap<>();
                adj.put("id", "adj_stub_" + now.toEpochSecond());
                adj.put("action", "refund".equals(outcome) ? "refund" : "chargeback");
                adj.put("type", "full");
                adj.put("status", "approved");
                adj.put("transaction_id", txn);
                adj.put("totals", Map.of("total", String.valueOf(amount), "currency_code", currency));
                out.add(paddleEvent("adjustment.created", adj));
            }
            default -> throw new Refusal(400, "invalid_outcome", "Unknown outcome.");
        }
        return out.stream().map(e -> {
            String body = json.writeValueAsString(e);
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String header = "ts=" + ts + ";h1=" + hmac(props.paddleWebhookSecret(), ts + ":" + body);
            if (!paddleSignatureValid(body, header)) {
                throw new IllegalStateException("stub signature");
            }
            return handlePaddle(body);
        }).toList();
    }

    private static Map<String, Object> paddleEvent(String type, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", "evt_stub_" + UUID.randomUUID().toString().replace("-", ""));
        e.put("event_type", type);
        e.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        e.put("data", data);
        return e;
    }

    private static Map<String, Object> paddleSubscriptionData(String id, String status, OffsetDateTime start,
                                                              OffsetDateTime end, Map<String, Object> custom) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("status", status);
        d.put("custom_data", custom);
        if (start != null) {
            d.put("current_billing_period", Map.of("starts_at", start.toString(), "ends_at", end.toString()));
        }
        return d;
    }

    private static Map<String, Object> paddleTransactionData(String subscription, Map<String, Object> custom,
                                                             OffsetDateTime start, OffsetDateTime end, long amount,
                                                             String currency, String country, String method) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", "txn_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        d.put("status", "completed");
        d.put("subscription_id", subscription);
        d.put("custom_data", custom);
        d.put("currency_code", currency);
        d.put("billing_period", Map.of("starts_at", start.toString(), "ends_at", end.toString()));
        d.put("details", Map.of("totals", Map.of("grand_total", String.valueOf(amount))));
        d.put("address", Map.of("country_code", country));
        d.put("payments", List.of(Map.of("method_details", Map.of("type", method == null || method.isBlank() ? "card" : method))));
        return d;
    }

    // ---- helpers ----

    static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime iso(JsonNode text) {
        try {
            return text.isString() && !text.asString().isBlank() ? OffsetDateTime.parse(text.asString()) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static OffsetDateTime time(JsonNode epochSeconds) {
        return epochSeconds.isNumber() ? Instant.ofEpochSecond(epochSeconds.asLong()).atOffset(ZoneOffset.UTC) : null;
    }

    private static final String SUB_SELECT = """
            SELECT id, plan, status, billing_country, currency, amount_minor, cancel_at_period_end, current_start,
                   current_end, ended_at, created_at
              FROM subscription
            """;

    private static Subscription sub(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Subscription(rs.getObject("id", UUID.class), rs.getString("plan"), rs.getString("status"),
                rs.getString("billing_country"), rs.getString("currency"), BigDecimal.valueOf(rs.getLong("amount_minor"), 2),
                rs.getBoolean("cancel_at_period_end"), rs.getObject("current_start", OffsetDateTime.class),
                rs.getObject("current_end", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
