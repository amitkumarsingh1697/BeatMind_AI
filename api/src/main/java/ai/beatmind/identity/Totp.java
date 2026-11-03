package ai.beatmind.identity;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Time-based one-time passwords (RFC 6238): 6 digits, 30-second steps, HMAC-SHA1. */
final class Totp {

    static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    static long step(Instant now) {
        return now.getEpochSecond() / STEP_SECONDS;
    }

    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The step a code matches, allowing one step of clock drift either way, or -1. Steps at or
     * before {@code lastStep} are refused, so a code works only once.
     */
    static long verify(byte[] key, String code, Instant now, long lastStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long current = step(now);
        for (long s = current - 1; s <= current + 1; s++) {
            if (s > lastStep && Secrets.constantTimeEquals(code(key, s), code)) {
                return s;
            }
        }
        return -1;
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }
}
