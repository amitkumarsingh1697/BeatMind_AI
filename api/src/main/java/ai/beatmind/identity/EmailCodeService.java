package ai.beatmind.identity;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email one-time codes (ADR-008) with the limits from threat model TM-01: a code lasts 10 minutes
 * and allows 5 attempts; at most 5 codes per email and 30 per IP address in an hour.
 */
@Service
class EmailCodeService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_CODES_PER_EMAIL_PER_HOUR = 5;
    static final int MAX_CODES_PER_IP_PER_HOUR = 30;

    /** Thrown when a caller asks for too many codes. */
    static class TooManyRequests extends RuntimeException {
        TooManyRequests() {
            super("Too many sign-in codes requested. Try again later.");
        }
    }

    private final JdbcClient jdbc;
    private final Secrets secrets;
    private final EmailSender sender;
    private final int maxCodesPerIp;

    /**
     * {@code maxCodesPerIp} can be raised for a shared local stack, where every request comes from
     * the web container's address. In staging Cloudflare supplies the real client address.
     */
    EmailCodeService(JdbcClient jdbc, Secrets secrets, EmailSender sender,
                     @Value("${beatmind.auth.max-codes-per-ip-per-hour:" + MAX_CODES_PER_IP_PER_HOUR + "}") int maxCodesPerIp) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.sender = sender;
        this.maxCodesPerIp = maxCodesPerIp;
    }

    /** Creates and sends a code. Returns the code itself, for the local dev-codes mode only. */
    @Transactional
    String start(String email, String clientIp, String locale) {
        String normalized = UserStore.normalize(email);
        String ipHash = secrets.hmac("ip:" + clientIp);
        long perEmail = jdbc.sql("""
                SELECT count(*) FROM email_code
                 WHERE lower(email) = :email AND created_at > now() - interval '1 hour'
                """).param("email", normalized).query(Long.class).single();
        long perIp = jdbc.sql("""
                SELECT count(*) FROM email_code WHERE ip_hash = :ip AND created_at > now() - interval '1 hour'
                """).param("ip", ipHash).query(Long.class).single();
        if (perEmail >= MAX_CODES_PER_EMAIL_PER_HOUR || perIp >= maxCodesPerIp) {
            throw new TooManyRequests();
        }
        String code = secrets.sixDigitCode();
        jdbc.sql("""
                INSERT INTO email_code (id, email, code_hash, ip_hash, expires_at)
                VALUES (:id, :email, :hash, :ip, now() + make_interval(secs => :ttl))
                """)
                .param("id", UUID.randomUUID())
                .param("email", normalized)
                .param("hash", hashCode(normalized, code))
                .param("ip", ipHash)
                .param("ttl", CODE_TTL.toSeconds())
                .update();
        sender.sendSignInCode(normalized, code, locale);
        return code;
    }

    /**
     * Checks a code against the newest live code for the email. A wrong guess uses up one attempt.
     * Returns the normalized email on success.
     */
    @Transactional(noRollbackFor = Exception.class)
    Optional<String> verify(String email, String code) {
        String normalized = UserStore.normalize(email);
        record Live(UUID id, String hash, int attempts) {}
        Optional<Live> live = jdbc.sql("""
                SELECT id, code_hash, attempts FROM email_code
                 WHERE lower(email) = :email AND used_at IS NULL AND expires_at > now()
                 ORDER BY created_at DESC LIMIT 1
                 FOR UPDATE
                """)
                .param("email", normalized)
                .query((rs, n) -> new Live(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)))
                .optional();
        if (live.isEmpty() || live.get().attempts() >= MAX_ATTEMPTS) {
            return Optional.empty();
        }
        String candidate = code == null ? "" : code.strip();
        if (!Secrets.constantTimeEquals(live.get().hash(), hashCode(normalized, candidate))) {
            jdbc.sql("UPDATE email_code SET attempts = attempts + 1 WHERE id = :id").param("id", live.get().id()).update();
            return Optional.empty();
        }
        jdbc.sql("UPDATE email_code SET used_at = now() WHERE id = :id").param("id", live.get().id()).update();
        return Optional.of(normalized);
    }

    private String hashCode(String email, String code) {
        return secrets.hmac("code:" + email + ":" + code);
    }
}
