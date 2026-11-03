package ai.beatmind.billing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The few Razorpay calls we make (INT-09): create a subscription, cancel it, refund a payment. In
 * stub mode nothing leaves the machine and ids are made up, so the rest of billing runs the same.
 */
@Component
class RazorpayClient {

    record Created(String subscriptionId, String shortUrl) {}

    private final BillingProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RazorpayClient(BillingProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    String planId(String plan, String variant) {
        String key = plan + "-" + variant;
        if (props.stub()) {
            return "plan_stub_" + plan + "_" + variant;
        }
        String id = props.razorpay().planIds().get(key);
        if (id == null) {
            throw new IllegalStateException("No Razorpay plan id for " + key + " (RAZORPAY_PLANS)");
        }
        return id;
    }

    /** Monthly subscription for 10 years; Razorpay charges each month until it is cancelled. */
    Created createSubscription(String planId, UUID userId, UUID subscriptionId) {
        if (props.stub()) {
            return new Created("sub_stub_" + subscriptionId.toString().replace("-", "").substring(0, 14), null);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan_id", planId);
        body.put("total_count", 120);
        body.put("customer_notify", 1);
        body.put("notes", Map.of("beatmind_user", userId.toString(), "beatmind_subscription", subscriptionId.toString()));
        JsonNode r = post("/v1/subscriptions", body);
        return new Created(r.path("id").asString(), r.path("short_url").asString(null));
    }

    /** Cancels at the end of the current cycle, so the user keeps what they paid for (REQ-F-43). */
    void cancelAtCycleEnd(String providerSubscriptionId) {
        if (!props.stub()) {
            post("/v1/subscriptions/" + providerSubscriptionId + "/cancel", Map.of("cancel_at_cycle_end", 1));
        }
    }

    void cancelNow(String providerSubscriptionId) {
        if (!props.stub()) {
            post("/v1/subscriptions/" + providerSubscriptionId + "/cancel", Map.of("cancel_at_cycle_end", 0));
        }
    }

    void refund(String paymentId) {
        if (!props.stub()) {
            post("/v1/payments/" + paymentId + "/refund", Map.of("speed", "normal"));
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        String auth = Base64.getEncoder().encodeToString(
                (props.razorpay().keyId() + ":" + props.razorpay().keySecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.razorpay().apiUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("Razorpay " + path + " returned " + res.statusCode());
            }
            return json.readTree(res.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Razorpay " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
