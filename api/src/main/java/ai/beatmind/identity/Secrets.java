package ai.beatmind.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Random tokens, hashes and HMACs for sign-in. */
@Component
class Secrets {

    private static final Logger log = LoggerFactory.getLogger(Secrets.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] pepper;

    Secrets(AuthProperties props) {
        if (props.devCodes()) {
            log.warn("BEATMIND_AUTH_DEV_CODES is on: sign-in codes are returned to the browser. Local development only.");
        }
        if (props.pepper().isBlank()) {
            log.warn("BEATMIND_AUTH_PEPPER is not set; using a random one. Sign-in codes will not survive a restart.");
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            this.pepper = random;
        } else {
            this.pepper = props.pepper().getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A URL-safe random token with 256 bits of entropy. */
    String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A 6-digit one-time code. */
    String sixDigitCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 as base64url, for PKCE code challenges. */
    String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Encrypts a small secret (a TOTP key) with AES-GCM under a key derived from the pepper. */
    String seal(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, sealKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] sealed = c.doFinal(plain);
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] open(String sealed) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, sealKey(), new javax.crypto.spec.GCMParameterSpec(128, in, 0, 12));
            return c.doFinal(in, 12, in.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot open sealed secret (was the pepper changed?)", e);
        }
    }

    private SecretKeySpec sealKey() throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
        return new SecretKeySpec(mac.doFinal("beatmind-mfa-seal-v1".getBytes(StandardCharsets.UTF_8)), "AES");
    }

    static SecureRandom random() {
        return RANDOM;
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
