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
 * <p>Razorpay is the source of truth for money; its signed webhooks are the only thing that turns a
 * plan on, settles a billing period or reverses one. Each webhook is processed once (REQ-SEC-08).
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

    /** What the web app needs to open Razorpay Checkout (or the stub dialog). */
    public record CheckoutStart(UUID id, String mode, String keyId, String providerSubscriptionId, String shortUrl,
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
    private final BillingProperties props;
    private final ApplicationEventPublisher events;

    Billing(JdbcClient jdbc, JsonMapper json, PricingService pricing, Accounts accounts, RazorpayClient razorpay,
            BillingProperties props, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.pricing = pricing;
        this.accounts = accounts;
        this.razorpay = razorpay;
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
     * Starts a subscription at the billing country's price (REQ-F-41). India only until the merchant
     * of record is in place (US-035, Sprint 6).
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
        if (!"IN".equals(quote.country())) {
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
        RazorpayClient.Created created = razorpay.createSubscription(razorpay.planId(plan, quote.variant()), userId, id);
        jdbc.sql("""
                INSERT INTO subscription (id, user_id, plan, billing_country, price_band, variant, currency, amount_minor,
                                          provider, provider_subscription_id)
                VALUES (:id, :user, :plan, :country, :band, :variant, :currency, :amount, 'razorpay', :provider)
                """)
                .param("id", id).param("user", userId).param("plan", plan).param("country", quote.country())
                .param("band", quote.band() == null ? null : quote.band().name()).param("variant", quote.variant())
                .param("currency", quote.currency())
                .param("amount", price.monthly().movePointRight(2).longValueExact())
                .param("provider", created.subscriptionId())
                .update();
        accounts.setBillingCountry(userId, quote.country());
        return new CheckoutStart(id, props.mode(), props.razorpay().keyId(), created.subscriptionId(), created.shortUrl(),
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
            razorpay.cancelAtCycleEnd(providerId(s.id()));
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
                razorpay.cancelNow(providerId(s.id()));
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
                .param("amount", payment.path("amount").asLong(sub.amountMinor()))
                .param("currency", payment.path("currency").asString(sub.currency()))
                .param("method", payment.path("method").asString(null)).param("status", mismatch ? "reversed" : "settled")
                .param("mismatch", mismatch)
                .update();
        if (inserted == 0) {
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
            String provider = providerId(p.subscriptionId());
            razorpay.cancelNow(provider);
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
        record S(UUID id, String provider, long amount, OffsetDateTime start, OffsetDateTime end) {}
        S s = jdbc.sql("""
                SELECT id, provider_subscription_id, amount_minor, current_start, current_end FROM subscription
                 WHERE user_id = :user ORDER BY created_at DESC LIMIT 1
                """).param("user", userId)
                .query((rs, n) -> new S(rs.getObject("id", UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class)))
                .optional().orElseThrow(() -> new Refusal(404, "no_plan", "Start a checkout first."));
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
