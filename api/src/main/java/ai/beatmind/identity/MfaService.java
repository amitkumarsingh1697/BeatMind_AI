package ai.beatmind.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Second factor (US-031, REQ-SEC-06, TM-10): TOTP from an authenticator app, with ten one-time
 * recovery codes. Mandatory for admins, optional for everyone else. After 5 wrong codes in 15
 * minutes the account must wait (TM-01 style throttling).
 */
@Service
class MfaService {

    static final String ISSUER = "BeatMind AI";
    static final int MAX_FAILURES = 5;
    static final long LOCK_MINUTES = 15;
    static final int RECOVERY_CODES = 10;

    record Status(boolean enrolled, boolean pending) {}

    record Setup(String secret, String otpauthUri) {}

    enum Result { OK, WRONG, LOCKED, NOT_ENROLLED }

    private final JdbcClient jdbc;
    private final Secrets secrets;
    private Clock clock = Clock.systemUTC();

    MfaService(JdbcClient jdbc, Secrets secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    /** For tests. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    Status status(UUID userId) {
        return jdbc.sql("SELECT confirmed_at IS NOT NULL FROM user_mfa WHERE user_id = :user")
                .param("user", userId).query(Boolean.class).optional()
                .map(confirmed -> new Status(confirmed, !confirmed))
                .orElse(new Status(false, false));
    }

    /** Starts (or restarts) enrolment. Refused once a factor is confirmed. */
    @Transactional
    Optional<Setup> setup(UUID userId, String email) {
        if (status(userId).enrolled()) {
            return Optional.empty();
        }
        byte[] key = new byte[20];
        Secrets.random().nextBytes(key);
        jdbc.sql("""
                INSERT INTO user_mfa (user_id, totp_secret_enc) VALUES (:user, :secret)
                ON CONFLICT (user_id) DO UPDATE SET totp_secret_enc = excluded.totp_secret_enc, last_step = 0,
                       failures = 0, failures_since = NULL, created_at = now()
                """)
                .param("user", userId).param("secret", secrets.seal(key)).update();
        String secret = Totp.base32(key);
        String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer="
                + URLEncoder.encode(ISSUER, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=6&period=" + Totp.STEP_SECONDS;
        return Optional.of(new Setup(secret, uri));
    }

    /** Confirms enrolment with a first code. Returns the recovery codes, shown once. */
    @Transactional
    Optional<List<String>> confirm(UUID userId, String code) {
        if (status(userId).enrolled() || check(userId, code, false) != Result.OK) {
            return Optional.empty();
        }
        jdbc.sql("UPDATE user_mfa SET confirmed_at = now() WHERE user_id = :user").param("user", userId).update();
        jdbc.sql("DELETE FROM mfa_recovery_code WHERE user_id = :user").param("user", userId).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String c = secrets.token().replaceAll("[^A-Za-z0-9]", "").substring(0, 10).toLowerCase();
            codes.add(c.substring(0, 5) + "-" + c.substring(5));
            jdbc.sql("INSERT INTO mfa_recovery_code (user_id, code_hash) VALUES (:user, :hash)")
                    .param("user", userId).param("hash", secrets.hmac("recovery:" + codes.getLast())).update();
        }
        return Optional.of(codes);
    }

    /** Checks a TOTP code or a recovery code for an enrolled account. */
    @Transactional
    Result verify(UUID userId, String code) {
        if (!status(userId).enrolled()) {
            return Result.NOT_ENROLLED;
        }
        return check(userId, code, true);
    }

    private Result check(UUID userId, String rawCode, boolean allowRecovery) {
        record Row(String secret, long lastStep, int failures, OffsetDateTime since) {}
        Row row = jdbc.sql("SELECT totp_secret_enc, last_step, failures, failures_since FROM user_mfa WHERE user_id = :user FOR UPDATE")
                .param("user", userId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getObject(4, OffsetDateTime.class)))
                .optional().orElse(null);
        if (row == null) {
            return Result.NOT_ENROLLED;
        }
        Instant now = clock.instant();
        boolean windowOpen = row.since() != null && row.since().toInstant().isAfter(now.minusSeconds(LOCK_MINUTES * 60));
        if (windowOpen && row.failures() >= MAX_FAILURES) {
            return Result.LOCKED;
        }
        String code = rawCode == null ? "" : rawCode.strip().replace(" ", "");
        long step = Totp.verify(secrets.open(row.secret()), code, now, row.lastStep());
        boolean ok = step >= 0;
        if (ok) {
            jdbc.sql("UPDATE user_mfa SET last_step = :step, failures = 0, failures_since = NULL WHERE user_id = :user")
                    .param("step", step).param("user", userId).update();
        } else if (allowRecovery && code.matches("[a-z0-9]{5}-[a-z0-9]{5}")) {
            ok = jdbc.sql("""
                    UPDATE mfa_recovery_code SET used_at = now()
                     WHERE user_id = :user AND code_hash = :hash AND used_at IS NULL
                    """)
                    .param("user", userId).param("hash", secrets.hmac("recovery:" + code)).update() == 1;
        }
        if (ok) {
            return Result.OK;
        }
        jdbc.sql("""
                UPDATE user_mfa SET failures = CASE WHEN :open THEN failures + 1 ELSE 1 END,
                       failures_since = CASE WHEN :open THEN failures_since ELSE :now END
                 WHERE user_id = :user
                """)
                .param("open", windowOpen).param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .param("user", userId).update();
        return Result.WRONG;
    }

    /** Recovery codes not yet used. */
    int recoveryCodesLeft(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM mfa_recovery_code WHERE user_id = :user AND used_at IS NULL")
                .param("user", userId).query(Integer.class).single();
    }
}
