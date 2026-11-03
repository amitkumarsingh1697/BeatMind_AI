package ai.beatmind.identity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The account side of data export and erasure (US-011, REQ-NFR-05, DPIA sections 6 and 7). Other
 * modules use this to read what the account holds and to close it.
 */
@Service
public class Accounts {

    /** Everything stored on the account row, for the export. */
    public record Profile(UUID id, String email, boolean googleLinked, LocalDate birthMonth, String termsVersion,
                          String locale, String plan, OffsetDateTime createdAt, OffsetDateTime lastSignInAt) {}

    private final JdbcClient jdbc;
    private final SessionService sessions;

    Accounts(JdbcClient jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    public Optional<Profile> profile(UUID id) {
        return jdbc.sql("""
                SELECT id, email, google_sub IS NOT NULL AS google, birth_month, terms_version, locale, plan,
                       created_at, last_sign_in_at
                  FROM app_user WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, n) -> new Profile(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getBoolean("google"), rs.getObject("birth_month", LocalDate.class),
                        rs.getString("terms_version"), rs.getString("locale"), rs.getString("plan"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_sign_in_at", OffsetDateTime.class)))
                .optional();
    }

    /**
     * Closes the account. The row stays as a tombstone with no personal data, so certificates and
     * the provenance chain stay valid (DPIA 6): the email becomes an unroutable placeholder, and
     * the Google link, birth month, sessions, second factor, pending sign-in codes and any
     * waitlist entry for the email are deleted. Signing in again with the same email starts a new,
     * empty account. Call inside the caller's transaction.
     */
    public void close(UUID id) {
        String email = jdbc.sql("SELECT email FROM app_user WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).query(String.class).optional().orElse(null);
        if (email == null) {
            return;
        }
        jdbc.sql("DELETE FROM email_code WHERE lower(email) = lower(:email)").param("email", email).update();
        jdbc.sql("DELETE FROM waitlist_signup WHERE lower(email) = lower(:email)").param("email", email).update();
        jdbc.sql("DELETE FROM user_session WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM mfa_recovery_code WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM user_mfa WHERE user_id = :id").param("id", id).update();
        jdbc.sql("""
                UPDATE app_user
                   SET email = 'deleted+' || id || '@deleted.invalid', google_sub = NULL, birth_month = NULL,
                       daily_cap_override = NULL, billing_country = NULL, deleted_at = now()
                 WHERE id = :id
                """).param("id", id).update();
    }

    /** The plan the account is on: free, creator or pro. Billing sets it from payments (US-034). */
    public void setPlan(UUID id, String plan) {
        jdbc.sql("UPDATE app_user SET plan = :plan WHERE id = :id AND deleted_at IS NULL")
                .param("plan", plan).param("id", id).update();
    }

    /** REQ-F-41: the country of the payment method, which decides the price. */
    public void setBillingCountry(UUID id, String country) {
        jdbc.sql("UPDATE app_user SET billing_country = :c WHERE id = :id").param("c", country).param("id", id).update();
    }

    public Optional<String> billingCountry(UUID id) {
        return jdbc.sql("SELECT billing_country FROM app_user WHERE id = :id").param("id", id)
                .query((rs, n) -> Optional.ofNullable(rs.getString(1))).optional().flatMap(o -> o);
    }

    /** The Set-Cookie value that signs the browser out. */
    public String signOutCookie() {
        return sessions.clearCookie();
    }
}
