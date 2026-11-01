package ai.beatmind.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes sign-in records once they are no longer needed (DPIA data minimisation): codes after a
 * day (the hourly rate limits only look back one hour), and sessions and Google round trips once
 * they have expired.
 */
@Component
class IdentityCleanup {

    private final JdbcClient jdbc;

    IdentityCleanup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void purge() {
        jdbc.sql("DELETE FROM email_code WHERE created_at < now() - interval '1 day'").update();
        jdbc.sql("DELETE FROM user_session WHERE expires_at < now() OR last_seen_at < now() - interval '30 days'").update();
        jdbc.sql("DELETE FROM oauth_state WHERE expires_at < now()").update();
    }
}
