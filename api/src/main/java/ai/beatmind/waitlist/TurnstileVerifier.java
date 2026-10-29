package ai.beatmind.waitlist;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Cloudflare Turnstile bot check (ARCH-09, TM-11). Disabled when no secret is configured. */
@Component
class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);
    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final String secret;
    private final RestClient http;

    TurnstileVerifier(@Value("${beatmind.turnstile.secret:}") String secret) {
        this.secret = secret;
        this.http = RestClient.create();
        if (secret.isBlank()) {
            log.warn("Turnstile secret not set: bot check disabled. Never run like this outside local development.");
        }
    }

    boolean verify(String token, String remoteIp) {
        if (secret.isBlank()) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", secret);
        form.add("response", token);
        if (remoteIp != null) {
            form.add("remoteip", remoteIp);
        }
        try {
            Map<?, ?> body = http.post().uri(VERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return body != null && Boolean.TRUE.equals(body.get("success"));
        } catch (RestClientException e) {
            log.warn("Turnstile verification failed: {}", e.getMessage());
            return false;
        }
    }
}
