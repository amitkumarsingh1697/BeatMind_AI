package ai.beatmind.generation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Run-time settings an admin can change ({@code app_setting}). */
@Component
class Settings {

    static final String FREE_DAILY_CREDITS = "free_daily_credits";

    private final JdbcClient jdbc;
    private final Entitlements defaults;

    Settings(JdbcClient jdbc, Entitlements defaults) {
        this.jdbc = jdbc;
        this.defaults = defaults;
    }

    int freeDailyCredits() {
        return get(FREE_DAILY_CREDITS).map(Integer::parseInt).orElse(defaults.freeDailyCredits());
    }

    void setFreeDailyCredits(int credits, UUID by) {
        jdbc.sql("""
                INSERT INTO app_setting (key, value, updated_at, updated_by) VALUES (:key, :value, now(), :by)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now(), updated_by = excluded.updated_by
                """)
                .param("key", FREE_DAILY_CREDITS)
                .param("value", Integer.toString(credits))
                .param("by", by)
                .update();
    }

    private Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM app_setting WHERE key = :key").param("key", key).query(String.class).optional();
    }
}
