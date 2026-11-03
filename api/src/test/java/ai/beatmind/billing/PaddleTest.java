package ai.beatmind.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-035, ADR-009: outside India, checkout goes to Paddle as the merchant of record. Stub mode
 * plays Paddle's notifications through the real handler, signed the way Paddle signs them.
 */
@DatabaseTest
@AutoConfigureMockMvc
class PaddleTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    Billing billing;

    Cookie sam;
    UUID samId;

    @BeforeEach
    void setUp() throws Exception {
        SignInHelper.reset(jdbc);
        sam = SignInHelper.signInAdult(mvc, "sam@example.com");
        samId = jdbc.sql("SELECT id FROM app_user WHERE email = 'sam@example.com'").query(UUID.class).single();
    }

    private ResultActions checkout(String plan, String country) throws Exception {
        return mvc.perform(post("/v1/checkout").cookie(sam).header("CF-IPCountry", "IN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("plan", plan, "billingCountry", country))));
    }

    private ResultActions simulate(String outcome) throws Exception {
        return mvc.perform(post("/v1/billing/simulate").cookie(sam).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"" + outcome + "\",\"method\":\"card\"}"));
    }

    private static String signature(String body, long ts) {
        return "ts=" + ts + ";h1=" + Billing.hmac(BillingProperties.STUB_PADDLE_SECRET, ts + ":" + body);
    }

    @Test
    @Tag("TC-REQ-F-42-02")
    void outsideIndiaTheMerchantOfRecordBillsAndThePlanFollowsItsWebhooks() throws Exception {
        // Browsing from India, billing address in the UK: the UK band in US dollars, plus tax that
        // Paddle adds at checkout.
        checkout("creator", "GB").andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("paddle"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.includesTax").value(false))
                .andExpect(jsonPath("$.priceId").value("pri_stub_creator_A"))
                .andExpect(jsonPath("$.environment").value("sandbox"));
        assertThat(jdbc.sql("SELECT provider_subscription_id FROM subscription").query(String.class).single())
                .startsWith("pending_");
        simulate("charged").andExpect(jsonPath("$.outcomes[0]").value("authenticated"))
                .andExpect(jsonPath("$.outcomes[1]").value("settled"));
        assertThat(jdbc.sql("SELECT provider_subscription_id FROM subscription").query(String.class).single())
                .startsWith("sub_stub_");
        mvc.perform(get("/v1/me").cookie(sam)).andExpect(jsonPath("$.plan").value("creator"));
        mvc.perform(get("/v1/billing").cookie(sam))
                .andExpect(jsonPath("$.subscription.status").value("active"))
                .andExpect(jsonPath("$.periods[0].status").value("settled"))
                .andExpect(jsonPath("$.periods[0].currency").value("USD"))
                .andExpect(jsonPath("$.periods[0].paymentMethod").value("card"));

        // A failed renewal Paddle is retrying keeps the plan pending; a chargeback ends it and
        // reverses the period (REQ-F-57).
        simulate("pending").andExpect(jsonPath("$.outcomes[0]").value("pending"));
        simulate("dispute_lost").andExpect(jsonPath("$.outcomes[0]").value("reversed"));
        mvc.perform(get("/v1/me").cookie(sam)).andExpect(jsonPath("$.plan").value("free"));
        assertThat(jdbc.sql("SELECT reversal_reason FROM billing_period").query(String.class).single()).isEqualTo("chargeback");
    }

    @Test
    void theAddressCountryPaidFromBecomesTheBillingCountry() throws Exception {
        // REQ-F-41: Paddle prices by the address; if the person changed it in checkout, we follow it.
        checkout("pro", "GB").andExpect(status().isCreated());
        simulate("foreign_card").andExpect(jsonPath("$.outcomes[1]").value("settled"));
        assertThat(jdbc.sql("SELECT billing_country FROM subscription").query(String.class).single()).isEqualTo("DE");
        assertThat(jdbc.sql("SELECT billing_country FROM app_user WHERE id = :id").param("id", samId)
                .query(String.class).single()).isEqualTo("DE");
    }

    @Test
    void paddleWebhooksNeedAFreshSignatureAndAreProcessedOnce() throws Exception {
        checkout("creator", "US").andExpect(status().isCreated());
        UUID ours = jdbc.sql("SELECT id FROM subscription").query(UUID.class).single();
        String body = json.writeValueAsString(Map.of("event_id", "evt_1", "event_type", "subscription.created",
                "data", Map.of("id", "sub_real_1", "status", "active", "custom_data", Map.of("bmSubscriptionId", ours.toString()),
                        "current_billing_period", Map.of("starts_at", "2026-11-03T00:00:00Z", "ends_at", "2026-12-03T00:00:00Z"))));
        long now = Instant.now().getEpochSecond();

        // No header, a wrong secret, and a replay of an old notification are all refused.
        mvc.perform(post("/v1/webhooks/paddle").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/webhooks/paddle").header("Paddle-Signature", "ts=" + now + ";h1=" + Billing.hmac("wrong", now + ":" + body))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/webhooks/paddle").header("Paddle-Signature", signature(body, now - 3600))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(billing.paddleSignatureValid(body, signature(body, now), Instant.ofEpochSecond(now + 200))).isTrue();
        // A rotated secret sends two h1 values; either may match.
        assertThat(billing.paddleSignatureValid(body, "ts=" + now + ";h1=00;h1="
                + Billing.hmac(BillingProperties.STUB_PADDLE_SECRET, now + ":" + body))).isTrue();

        mvc.perform(post("/v1/webhooks/paddle").header("Paddle-Signature", signature(body, now))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("authenticated"));
        mvc.perform(post("/v1/webhooks/paddle").header("Paddle-Signature", signature(body, now))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("duplicate"));
        assertThat(jdbc.sql("SELECT provider_subscription_id FROM subscription").query(String.class).single())
                .isEqualTo("sub_real_1");

        // A cancellation scheduled in Paddle's portal is shown as "ends at period end".
        String scheduled = json.writeValueAsString(Map.of("event_id", "evt_2", "event_type", "subscription.updated",
                "data", Map.of("id", "sub_real_1", "status", "active", "scheduled_change", Map.of("action", "cancel"))));
        mvc.perform(post("/v1/webhooks/paddle").header("Paddle-Signature", signature(scheduled, now))
                .contentType(MediaType.APPLICATION_JSON).content(scheduled)).andExpect(status().isOk());
        mvc.perform(get("/v1/billing").cookie(sam)).andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true));
    }

    @Test
    void aPartialRefundKeepsOwnershipAndAFullRefundReversesIt() throws Exception {
        checkout("creator", "CA").andExpect(status().isCreated());
        simulate("charged");
        String txn = jdbc.sql("SELECT provider_payment_id FROM billing_period").query(String.class).single();
        long amount = jdbc.sql("SELECT amount_minor FROM billing_period").query(Long.class).single();
        long now = Instant.now().getEpochSecond();
        String partial = json.writeValueAsString(Map.of("event_id", "evt_p", "event_type", "adjustment.created",
                "data", Map.of("action", "refund", "type", "partial", "status", "approved", "transaction_id", txn,
                        "totals", Map.of("total", String.valueOf(amount / 2)))));
        mvc.perform(post("/v1/webhooks/paddle").header("Paddle-Signature", signature(partial, now))
                .contentType(MediaType.APPLICATION_JSON).content(partial))
                .andExpect(jsonPath("$.outcome").value("partial_refund"));
        // Adjustments Paddle has not approved yet change nothing.
        String pending = json.writeValueAsString(Map.of("event_id", "evt_q", "event_type", "adjustment.created",
                "data", Map.of("action", "refund", "type", "full", "status", "pending_approval", "transaction_id", txn)));
        mvc.perform(post("/v1/webhooks/paddle").header("Paddle-Signature", signature(pending, now))
                .contentType(MediaType.APPLICATION_JSON).content(pending))
                .andExpect(jsonPath("$.outcome").value("adjustment_pending_approval"));
        mvc.perform(get("/v1/me").cookie(sam)).andExpect(jsonPath("$.plan").value("creator"));
        simulate("refund").andExpect(jsonPath("$.outcomes[0]").value("reversed"));
        mvc.perform(get("/v1/me").cookie(sam)).andExpect(jsonPath("$.plan").value("free"));
    }
}
