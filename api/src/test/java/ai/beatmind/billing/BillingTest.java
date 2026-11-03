package ai.beatmind.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
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
 * US-033, US-034, US-036, US-037: price by billing country, Razorpay checkout and webhooks (stub
 * mode plays Razorpay's events through the real, signature-checked handler), ownership that vests
 * on payment, the chargeback rule, and cancellation.
 */
@DatabaseTest
@AutoConfigureMockMvc
class BillingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    Billing billing;

    Cookie asha;
    UUID ashaId;

    @BeforeEach
    void setUp() throws Exception {
        SignInHelper.reset(jdbc);
        asha = SignInHelper.signInAdult(mvc, "asha@example.com");
        ashaId = jdbc.sql("SELECT id FROM app_user WHERE email = 'asha@example.com'").query(UUID.class).single();
    }

    private ResultActions checkout(Cookie who, String plan, String country) throws Exception {
        return mvc.perform(post("/v1/checkout").cookie(who).header("CF-IPCountry", "US")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("plan", plan, "billingCountry", country))));
    }

    private ResultActions simulate(Cookie who, String outcome) throws Exception {
        return mvc.perform(post("/v1/billing/simulate").cookie(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"" + outcome + "\",\"method\":\"upi\"}"));
    }

    private UUID song(Cookie who, String prompt) throws Exception {
        String body = mvc.perform(post("/v1/generations").cookie(who).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "song", "prompt", prompt, "language", "hi"))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("songId").asString());
    }

    private String rights(UUID song) {
        return jdbc.sql("SELECT rights_status FROM song WHERE id = :id").param("id", song).query(String.class).single();
    }

    private void finish(UUID song) {
        UUID job = jdbc.sql("SELECT job_id FROM song WHERE id = :id").param("id", song).query(UUID.class).single();
        String take = """
                {"key":"takes/%1$s/take-%2$d.wav","format":"wav","runner":"stub","watermarkId":%2$d,
                 "assets":{"mp3_192":{"key":"k%2$d.mp3","contentType":"audio/mpeg","sha256":"s%2$d"},
                           "mp3_320":{"key":"k%2$d-320.mp3","contentType":"audio/mpeg","sha256":"t%2$d"},
                           "wav":{"key":"k%2$d.wav","contentType":"audio/wav","sha256":"u%2$d"}}}""";
        jdbc.sql("UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:r AS jsonb) WHERE id = :id")
                .param("r", "{\"takes\":[" + take.formatted(job, 1) + "," + take.formatted(job, 2) + "]}")
                .param("id", job).update();
        // Takes are recorded by the job finalizer (generation module), run here on demand.
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(context.getBean("jobFinalizer"), "runOnce");
    }

    @Test
    @Tag("TC-REQ-F-41-01")
    void theBillingCountryNotTheBrowsingCountrySetsThePrice() throws Exception {
        // Browsing from the US (CF-IPCountry), paying from India: the Indian price applies.
        checkout(asha, "creator", "IN").andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.monthly").value(149))
                .andExpect(jsonPath("$.includesTax").value(true))
                .andExpect(jsonPath("$.mode").value("stub"))
                .andExpect(jsonPath("$.providerSubscriptionId").value(org.hamcrest.Matchers.startsWith("sub_stub_")));
        assertThat(jdbc.sql("SELECT amount_minor FROM subscription").query(Long.class).single()).isEqualTo(14900);
        assertThat(jdbc.sql("SELECT billing_country FROM app_user WHERE id = :id").param("id", ashaId)
                .query(String.class).single()).isEqualTo("IN");
        // Other countries wait for the merchant of record (US-035).
        Cookie sam = SignInHelper.signInAdult(mvc, "sam@example.com");
        checkout(sam, "creator", "GB").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("international_soon"));
        checkout(sam, "gold", "IN").andExpect(status().isBadRequest());
        // A card from another country on an Indian price is refunded and gives no plan.
        simulate(asha, "foreign_card").andExpect(jsonPath("$.outcomes[1]").value("billing_country_mismatch"));
        mvc.perform(get("/v1/me").cookie(asha)).andExpect(jsonPath("$.plan").value("free"));
        assertThat(jdbc.sql("SELECT reversal_reason FROM billing_period").query(String.class).single())
                .isEqualTo("billing_country_mismatch");
    }

    @Test
    @Tag("TC-REQ-F-42-01")
    void paymentTurnsThePlanOnAndSongsAreOwnedOnceItSettles() throws Exception {
        checkout(asha, "pro", "IN").andExpect(status().isCreated()).andExpect(jsonPath("$.monthly").value(349));
        // Before any payment the account is still free.
        mvc.perform(get("/v1/me").cookie(asha)).andExpect(jsonPath("$.plan").value("free"));
        simulate(asha, "charged").andExpect(jsonPath("$.outcomes[0]").value("authenticated"))
                .andExpect(jsonPath("$.outcomes[1]").value("settled"));
        mvc.perform(get("/v1/me").cookie(asha)).andExpect(jsonPath("$.plan").value("pro"));
        mvc.perform(get("/v1/billing").cookie(asha))
                .andExpect(jsonPath("$.subscription.status").value("active"))
                .andExpect(jsonPath("$.periods[0].status").value("settled"))
                .andExpect(jsonPath("$.periods[0].paymentMethod").value("upi"))
                .andExpect(jsonPath("$.periods[0].amount").value(349.00));
        UUID song = song(asha, "monsoon lofi");
        assertThat(rights(song)).isEqualTo("user_owned");
        assertThat(jdbc.sql("SELECT billing_period_id IS NOT NULL FROM song WHERE id = :id").param("id", song)
                .query(Boolean.class).single()).isTrue();
        // A second checkout is refused while the plan is live.
        checkout(asha, "creator", "IN").andExpect(status().isConflict());
    }

    @Test
    void webhooksNeedTheSignatureAndAreProcessedOnce() throws Exception {
        checkout(asha, "creator", "IN").andExpect(status().isCreated());
        String provider = jdbc.sql("SELECT provider_subscription_id FROM subscription").query(String.class).single();
        long now = java.time.Instant.now().getEpochSecond();
        String body = json.writeValueAsString(Map.of("event", "subscription.charged", "payload", Map.of(
                "subscription", Map.of("entity", Map.of("id", provider, "current_start", now, "current_end", now + 2_592_000)),
                "payment", Map.of("entity", Map.of("id", "pay_real1", "amount", 14900, "currency", "INR",
                        "status", "captured", "method", "card", "international", false)))));
        mvc.perform(post("/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", "0".repeat(64)).header("X-Razorpay-Event-Id", "evt_1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM billing_period").query(Long.class).single()).isZero();

        String signature = Billing.hmac(BillingProperties.STUB_WEBHOOK_SECRET, body);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-Razorpay-Signature", signature).header("X-Razorpay-Event-Id", "evt_1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value(i == 0 ? "settled" : "duplicate"));
        }
        // The same payment under a new event id still makes one period.
        mvc.perform(post("/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", signature).header("X-Razorpay-Event-Id", "evt_2"))
                .andExpect(jsonPath("$.outcome").value("duplicate_payment"));
        assertThat(jdbc.sql("SELECT count(*) FROM billing_period").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void songsMadeWhileARenewalIsPendingVestWhenItIsPaidAndLapseIfItIsNot() throws Exception {
        checkout(asha, "creator", "IN");
        simulate(asha, "charged");
        // The period ends; Razorpay has not charged the renewal yet.
        jdbc.sql("UPDATE billing_period SET starts_at = now() - interval '31 days', ends_at = now() - interval '1 hour'").update();
        jdbc.sql("UPDATE subscription SET current_start = now() - interval '31 days', current_end = now() - interval '1 hour'").update();
        simulate(asha, "pending");
        UUID waiting = song(asha, "made while the renewal is pending");
        assertThat(rights(waiting)).isEqualTo("vesting");
        finish(waiting);
        mvc.perform(post("/v1/songs/" + waiting + "/commercial-licence").cookie(asha))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ownership_pending"));
        mvc.perform(get("/v1/songs/" + waiting).cookie(asha))
                .andExpect(jsonPath("$.rights").value("You will own this song once this month's payment goes through."));

        simulate(asha, "renew").andExpect(jsonPath("$.outcomes[0]").value("settled"));
        assertThat(rights(waiting)).isEqualTo("user_owned");
        assertThat(ledger(waiting)).contains("ownership_vested");

        // Next time the renewal fails for good: songs still vesting lapse to the free-plan state.
        jdbc.sql("UPDATE billing_period SET ends_at = now() - interval '1 minute' WHERE ends_at > now()").update();
        simulate(asha, "pending");
        UUID lapsing = song(asha, "made before the subscription halted");
        assertThat(rights(lapsing)).isEqualTo("vesting");
        simulate(asha, "halted").andExpect(jsonPath("$.outcomes[0]").value("halted"));
        assertThat(rights(lapsing)).isEqualTo("bm_owned");
        assertThat(rights(waiting)).isEqualTo("user_owned");
        mvc.perform(get("/v1/me").cookie(asha)).andExpect(jsonPath("$.plan").value("free"));
    }

    @Test
    @Tag("TC-REQ-F-57-01")
    void aLostChargebackReturnsThatPeriodsSongsToTheFreePlanAndRevokesCertificates() throws Exception {
        checkout(asha, "creator", "IN");
        simulate(asha, "charged");
        UUID song = song(asha, "disputed month song");
        finish(song);
        String cert = json.readTree(mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(asha))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.scope").value("ownership_paid"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        simulate(asha, "dispute_lost").andExpect(jsonPath("$.outcomes[0]").value("reversed"));
        assertThat(rights(song)).isEqualTo("bm_owned");
        mvc.perform(get("/v1/certificates/" + cert)).andExpect(jsonPath("$.status").value("revoked"));
        assertThat(ledger(song)).contains("ownership_reversed");
        mvc.perform(get("/v1/me").cookie(asha)).andExpect(jsonPath("$.plan").value("free"));
        mvc.perform(get("/v1/billing").cookie(asha)).andExpect(jsonPath("$.periods[0].status").value("reversed"))
                .andExpect(jsonPath("$.periods[0].reversalReason").value("chargeback"));
    }

    @Test
    void aFullRefundReversesThePeriodToo() throws Exception {
        checkout(asha, "creator", "IN");
        simulate(asha, "charged");
        UUID song = song(asha, "refunded month song");
        simulate(asha, "refund").andExpect(jsonPath("$.outcomes[0]").value("reversed"));
        assertThat(rights(song)).isEqualTo("bm_owned");
    }

    @Test
    @Tag("TC-REQ-F-58-01")
    void cancellingKeepsThePlanToThePeriodEndAndOwnedSongsKeepTheirDownloads() throws Exception {
        checkout(asha, "creator", "IN");
        simulate(asha, "charged");
        UUID song = song(asha, "owned for good");
        finish(song);

        mvc.perform(post("/v1/billing/cancel").cookie(asha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true))
                .andExpect(jsonPath("$.plan").value("creator"));
        assertThat(billing.expire()).isZero();
        jdbc.sql("UPDATE subscription SET current_end = now() - interval '1 second'").update();
        assertThat(billing.expire()).isEqualTo(1);
        mvc.perform(get("/v1/me").cookie(asha)).andExpect(jsonPath("$.plan").value("free"));

        // REQ-F-58: still owned, still WAV, after the plan ends.
        assertThat(rights(song)).isEqualTo("user_owned");
        mvc.perform(get("/v1/songs/" + song).cookie(asha)).andExpect(jsonPath("$.downloadFormats[1]").value("wav"));
        mvc.perform(post("/v1/songs/" + song + "/takes/1/downloads").cookie(asha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"wav\"}")).andExpect(status().isOk());
        // New songs follow the free plan.
        assertThat(rights(song(asha, "after cancelling"))).isEqualTo("bm_owned");
        mvc.perform(post("/v1/billing/cancel").cookie(asha)).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheAccountStopsThePlan() throws Exception {
        checkout(asha, "creator", "IN");
        simulate(asha, "charged");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/me").cookie(asha)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmEmail\":\"asha@example.com\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT status FROM subscription").query(String.class).single()).isEqualTo("cancelled");
    }

    private String ledger(UUID song) {
        return jdbc.sql("SELECT string_agg(type, ',' ORDER BY seq) FROM provenance_event WHERE song_id = :s")
                .param("s", song).query(String.class).single();
    }

    @Autowired
    org.springframework.context.ApplicationContext context;
}
