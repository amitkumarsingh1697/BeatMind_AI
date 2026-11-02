package ai.beatmind.generation;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signed download links (US-026, REQ-F-20): the link names one stored file and stops working after
 * 15 minutes. Signed by the API, so it works the same with R2 in the cloud and a local store.
 */
@Component
class DownloadTokens {

    record Grant(UUID songId, int takeIndex, String asset, String format, Instant expiresAt) {}

    private static final Logger log = LoggerFactory.getLogger(DownloadTokens.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;
    private final Entitlements entitlements;
    private Clock clock = Clock.systemUTC();

    DownloadTokens(Entitlements entitlements) {
        this.entitlements = entitlements;
        if (entitlements.downloadSecret().isBlank()) {
            log.warn("BEATMIND_DOWNLOAD_SECRET is not set; download links will stop working on restart.");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = entitlements.downloadSecret().getBytes(StandardCharsets.UTF_8);
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    String issue(Grant grant) {
        String payload = grant.songId() + "|" + grant.takeIndex() + "|" + grant.asset() + "|" + grant.format() + "|"
                + grant.expiresAt().getEpochSecond();
        String body = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + B64.encodeToString(mac(body));
    }

    Instant expiry() {
        return Instant.now(clock).plus(entitlements.downloadLinkTtl());
    }

    /** The grant, if the token is genuine and has not expired. */
    Optional<Grant> check(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        byte[] sig;
        try {
            sig = B64D.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(sig, mac(body))) {
            return Optional.empty();
        }
        String[] parts = new String(B64D.decode(body), StandardCharsets.UTF_8).split("\\|");
        if (parts.length != 5) {
            return Optional.empty();
        }
        Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[4]));
        if (!Instant.now(clock).isBefore(expires)) {
            return Optional.empty();
        }
        return Optional.of(new Grant(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), parts[2], parts[3], expires));
    }

    private byte[] mac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(("download:" + body).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
