package ai.beatmind.identity;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accounts in {@code app_user}. */
@Repository
class UserStore {

    record User(UUID id, String email, String googleSub, LocalDate birthMonth, String termsVersion, String plan) {
        boolean profileComplete() {
            return birthMonth != null && termsVersion != null;
        }
    }

    private final JdbcClient jdbc;

    UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<User> find(UUID id) {
        return jdbc.sql("SELECT id, email, google_sub, birth_month, terms_version, plan FROM app_user WHERE id = :id")
                .param("id", id)
                .query(UserStore::map)
                .optional();
    }

    /** Finds the account for a verified email, creating it on first sign-in. */
    @Transactional
    User upsertByEmail(String email, String locale) {
        String normalized = normalize(email);
        jdbc.sql("""
                INSERT INTO app_user (id, email, locale, last_sign_in_at) VALUES (:id, :email, :locale, now())
                ON CONFLICT (lower(email)) DO UPDATE SET last_sign_in_at = now()
                """)
                .param("id", UUID.randomUUID())
                .param("email", normalized)
                .param("locale", locale)
                .update();
        return jdbc.sql("SELECT id, email, google_sub, birth_month, terms_version, plan FROM app_user WHERE lower(email) = :email")
                .param("email", normalized)
                .query(UserStore::map)
                .single();
    }

    /**
     * Finds the account for a Google subject. A Google account with a verified email that matches
     * an existing email account is linked to it.
     */
    @Transactional
    User upsertByGoogle(String sub, String email, String locale) {
        Optional<User> bySub = jdbc.sql("SELECT id, email, google_sub, birth_month, terms_version, plan FROM app_user WHERE google_sub = :sub")
                .param("sub", sub)
                .query(UserStore::map)
                .optional();
        if (bySub.isPresent()) {
            jdbc.sql("UPDATE app_user SET last_sign_in_at = now() WHERE id = :id").param("id", bySub.get().id()).update();
            return bySub.get();
        }
        User user = upsertByEmail(email, locale);
        if (user.googleSub() == null) {
            jdbc.sql("UPDATE app_user SET google_sub = :sub WHERE id = :id").param("sub", sub).param("id", user.id()).update();
        }
        return new User(user.id(), user.email(), sub, user.birthMonth(), user.termsVersion(), user.plan());
    }

    /** Stores the birth month only; the day was needed just for the 18+ check. */
    void completeProfile(UUID id, LocalDate birthDate, String termsVersion) {
        jdbc.sql("UPDATE app_user SET birth_month = :birth, terms_version = :terms WHERE id = :id")
                .param("birth", birthDate.withDayOfMonth(1))
                .param("terms", termsVersion)
                .param("id", id)
                .update();
    }

    /** Deletes the account and, by cascade, its sessions and jobs. */
    void delete(UUID id) {
        jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", id).update();
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static User map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("google_sub"),
                rs.getObject("birth_month", LocalDate.class),
                rs.getString("terms_version"),
                rs.getString("plan"));
    }
}
