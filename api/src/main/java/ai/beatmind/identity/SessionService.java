package ai.beatmind.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Server-side sessions (REQ-SEC-01, TM-02). The cookie holds a random 256-bit token; the database
 * holds only its SHA-256, so a database leak does not leak live sessions. Each sign-in gets a new
 * token, and a session ends after {@code sessionTtl}, or after {@code sessionIdle} without use.
 */
@Service
class SessionService {

    static final String COOKIE = "bm_session";
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    /** A live session. {@code mfaAt} is when it passed the second factor (US-031), or null. */
    record Session(UUID userId, OffsetDateTime lastSeenAt, OffsetDateTime mfaAt) {}

    private final JdbcClient jdbc;
    private final Secrets secrets;
    private final AuthProperties props;

    SessionService(JdbcClient jdbc, Secrets secrets, AuthProperties props) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.props = props;
    }

    /** Starts a session and returns the Set-Cookie header value. */
    String start(UUID userId) {
        String token = secrets.token();
        jdbc.sql("""
                INSERT INTO user_session (token_hash, user_id, expires_at)
                VALUES (:hash, :user, now() + make_interval(secs => :ttl))
                """)
                .param("hash", secrets.sha256(token))
                .param("user", userId)
                .param("ttl", props.sessionTtl().toSeconds())
                .update();
        return cookie(token, props.sessionTtl()).toString();
    }

    /** The live session for a token, refreshing its idle timer now and then. */
    Optional<Session> resolve(String token) {
        String hash = secrets.sha256(token);
        Optional<Session> session = jdbc.sql("""
                SELECT user_id, last_seen_at, mfa_at FROM user_session
                 WHERE token_hash = :hash AND expires_at > now()
                   AND last_seen_at > now() - make_interval(secs => :idle)
                """)
                .param("hash", hash)
                .param("idle", props.sessionIdle().toSeconds())
                .query((rs, n) -> new Session(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class)))
                .optional();
        session.filter(s -> s.lastSeenAt().isBefore(OffsetDateTime.now().minus(TOUCH_EVERY)))
                .ifPresent(s -> jdbc.sql("UPDATE user_session SET last_seen_at = now() WHERE token_hash = :hash")
                        .param("hash", hash).update());
        return session;
    }

    /** Marks the session as having passed the second factor. */
    void markMfa(String token) {
        jdbc.sql("UPDATE user_session SET mfa_at = now() WHERE token_hash = :hash")
                .param("hash", secrets.sha256(token)).update();
    }

    void end(String token) {
        jdbc.sql("DELETE FROM user_session WHERE token_hash = :hash").param("hash", secrets.sha256(token)).update();
    }

    String clearCookie() {
        return cookie("", Duration.ZERO).toString();
    }

    static Optional<String> tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
