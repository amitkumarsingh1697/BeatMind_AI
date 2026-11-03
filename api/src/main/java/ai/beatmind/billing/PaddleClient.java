package ai.beatmind.billing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The few Paddle Billing calls we make (US-035, ADR-009): cancel a subscription and refund a
 * transaction. Checkout itself opens in the browser with Paddle.js and the client token. Paddle is
 * the merchant of record, so it charges and remits VAT and sales tax. In stub mode nothing leaves
 * the machine.
 */
@Component
class PaddleClient {

    private final BillingProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    PaddleClient(BillingProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    /** The Paddle price for a plan and EXP-02 variant, or null when Paddle is not set up. */
    String priceId(String plan, String variant) {
        if (props.stub()) {
            return "pri_stub_" + plan + "_" + variant;
        }
        return props.paddle().priceIds().get(plan + "-" + variant);
    }

    String environment() {
        return "live".equals(props.mode()) ? "production" : "sandbox";
    }

    /** Ends at the end of the paid period (REQ-F-43), or at once (account deletion, chargeback). */
    void cancel(String providerSubscriptionId, boolean immediately) {
        if (props.stub() || providerSubscriptionId == null || providerSubscriptionId.startsWith("pending_")) {
            return;
        }
        post("/subscriptions/" + providerSubscriptionId + "/cancel",
                Map.of("effective_from", immediately ? "immediately" : "next_billing_period"));
    }

    /** A full refund of a completed transaction, as an adjustment Paddle approves. */
    void refund(String transactionId, String reason) {
        if (props.stub()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "refund");
        body.put("type", "full");
        body.put("transaction_id", transactionId);
        body.put("reason", reason);
        post("/adjustments", body);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.paddle().apiUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + props.paddle().apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("Paddle " + path + " returned " + res.statusCode());
            }
            return json.readTree(res.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Paddle " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
