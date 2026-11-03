package ai.beatmind.billing;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Billing settings ({@code beatmind.billing.*}).
 *
 * @param mode stub (local: checkout is simulated and its events go through the real webhook
 *     handler), test or live (Razorpay test or live keys)
 */
@ConfigurationProperties("beatmind.billing")
record BillingProperties(String mode, Razorpay razorpay) {

    /** Used to sign simulated webhooks in stub mode when no secret is set. */
    static final String STUB_WEBHOOK_SECRET = "stub-webhook-secret";

    BillingProperties {
        mode = mode == null || mode.isBlank() ? "stub" : mode.trim();
        razorpay = razorpay == null ? new Razorpay(null, null, null, null, null) : razorpay;
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
            Map<String, String> out = new HashMap<>();
            if (plans != null) {
                for (String pair : plans.split(",")) {
                    String[] kv = pair.trim().split("=", 2);
                    if (kv.length == 2 && !kv[0].isBlank()) {
                        out.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
            return out;
        }
    }
}
