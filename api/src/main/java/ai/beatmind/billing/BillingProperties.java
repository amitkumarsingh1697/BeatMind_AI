package ai.beatmind.billing;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Billing settings ({@code beatmind.billing.*}).
 *
 * @param mode stub (local: checkout is simulated and its events go through the real webhook
 *     handler), test (Razorpay test keys, Paddle sandbox) or live
 * @param razorpay payments in India (ADR-009)
 * @param paddle the merchant of record for every other country (US-035, ADR-009)
 */
@ConfigurationProperties("beatmind.billing")
record BillingProperties(String mode, Razorpay razorpay, Paddle paddle) {

    /** Used to sign simulated webhooks in stub mode when no secret is set. */
    static final String STUB_WEBHOOK_SECRET = "stub-webhook-secret";
    static final String STUB_PADDLE_SECRET = "stub-paddle-secret";

    BillingProperties {
        mode = mode == null || mode.isBlank() ? "stub" : mode.trim();
        razorpay = razorpay == null ? new Razorpay(null, null, null, null, null) : razorpay;
        paddle = paddle == null ? new Paddle(null, null, null, null, null) : paddle;
    }

    String paddleWebhookSecret() {
        String s = paddle.webhookSecret();
        return s == null || s.isBlank() ? (stub() ? STUB_PADDLE_SECRET : "") : s;
    }

    boolean stub() {
        return "stub".equals(mode);
    }

    String webhookSecret() {
        String s = razorpay.webhookSecret();
        return s == null || s.isBlank() ? (stub() ? STUB_WEBHOOK_SECRET : "") : s;
    }

    /**
     * @param plans Razorpay plan ids, "creator-A=plan_x,pro-A=plan_y"; a plan id is a price in
     *     Razorpay, so each plan and EXP-02 price variant has its own
     */
    record Razorpay(String apiUrl, String keyId, String keySecret, String webhookSecret, String plans) {

        Razorpay {
            apiUrl = apiUrl == null || apiUrl.isBlank() ? "https://api.razorpay.com" : apiUrl;
            keyId = keyId == null ? "" : keyId;
            keySecret = keySecret == null ? "" : keySecret;
        }

        Map<String, String> planIds() {
            return pairs(plans);
        }

        static Map<String, String> pairs(String text) {
            Map<String, String> out = new HashMap<>();
            if (text != null) {
                for (String pair : text.split(",")) {
                    String[] kv = pair.trim().split("=", 2);
                    if (kv.length == 2 && !kv[0].isBlank()) {
                        out.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
            return out;
        }
    }

    /**
     * @param apiUrl https://sandbox-api.paddle.com in test mode, https://api.paddle.com live
     * @param apiKey server-side API key
     * @param clientToken the public token Paddle.js opens checkout with
     * @param webhookSecret the notification destination's secret key
     * @param prices Paddle price ids, "creator-A=pri_x,pro-A=pri_y"; country prices are overrides
     *     on each price, set in Paddle to match the price bands (TODO.md)
     */
    record Paddle(String apiUrl, String apiKey, String clientToken, String webhookSecret, String prices) {

        Paddle {
            apiUrl = apiUrl == null || apiUrl.isBlank() ? "https://api.paddle.com" : apiUrl;
            apiKey = apiKey == null ? "" : apiKey;
            clientToken = clientToken == null ? "" : clientToken;
        }

        Map<String, String> priceIds() {
            return Razorpay.pairs(prices);
        }
    }
}
