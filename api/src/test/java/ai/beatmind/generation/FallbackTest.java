package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;

/** US-016: paid songs use the Lyria fallback when the GPU queue is long, within a daily spend cap. */
@DatabaseTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "beatmind.fallback.enabled=true",
        "beatmind.fallback.after-wait-seconds=120",
        "beatmind.fallback.languages=en",
        "beatmind.fallback.usd-per-take=0.04",
        "beatmind.fallback.daily-cap-usd=0.20"})
class FallbackTest extends SongApiSupport {

    Cookie paid;
    Cookie free;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        paid = adult("paid@example.com");
        setPlan("paid@example.com", "creator");
        paid = SignInHelper.signIn(mvc, "paid@example.com");
        free = adult("free@example.com");
    }

    private void longQueue() {
        // 10 songs waiting, 40 s each, one worker: about 7 minutes.
        jdbc.sql("""
                INSERT INTO generation_job (id, kind, status, payload)
                SELECT gen_random_uuid(), 'song', 'queued', '{}'::jsonb FROM generate_series(1, 10)
                """).update();
    }

    private String provider(UUID job) {
        return jdbc.sql("SELECT provider FROM generation_job WHERE id = :id").param("id", job).query(String.class).single();
    }

    @Test
    void aShortQueueStaysOnOurGpus() throws Exception {
        assertThat(provider(made(paid, "short queue song")[0])).isEqualTo("self");
    }

    @Test
    void aLongQueueSendsPaidSongsToTheFallbackUntilTheCap() throws Exception {
        longQueue();
        assertThat(provider(made(free, "free song waits")[0])).isEqualTo("self");
        UUID[] first = made(paid, "paid song one");
        assertThat(provider(first[0])).isEqualTo("lyria");
        var payload = json.readTree(jdbc.sql("SELECT payload::text FROM generation_job WHERE id = :id").param("id", first[0])
                .query(String.class).single());
        assertThat(payload.get("provider").asString()).isEqualTo("lyria");
        assertThat(events(first[1])).contains("routed_to_fallback");
        // The fallback jobs do not count in the GPU queue position.
        assertThat(jdbc.sql("SELECT cost_estimate_usd FROM generation_job WHERE id = :id").param("id", first[0])
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.08");

        // The worker reports the actual cost; it replaces the estimate.
        jdbc.sql("""
                UPDATE generation_job SET status = 'succeeded', finished_at = now(),
                       result = '{"takes":[{"key":"a.wav","format":"wav","runner":"lyria"},{"key":"b.wav","format":"wav","runner":"lyria"}],"costUsd":0.06}'
                 WHERE id = :id
                """).param("id", first[0]).update();
        finalizer.runOnce();
        assertThat(jdbc.sql("SELECT cost_actual_usd FROM generation_job WHERE id = :id").param("id", first[0])
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.06");

        // Cap 0.20: 0.06 + 0.08 fits, a third does not.
        jdbc.sql("UPDATE generation_job SET status = 'dead', finalized_at = now() WHERE user_id IS NOT NULL").update();
        assertThat(provider(made(paid, "paid song two")[0])).isEqualTo("lyria");
        jdbc.sql("UPDATE generation_job SET status = 'dead', finalized_at = now() WHERE user_id IS NOT NULL").update();
        assertThat(provider(made(paid, "paid song three")[0])).isEqualTo("self");

        Cookie admin = SignInHelper.signInAdmin(mvc, "admin@example.com");
        mvc.perform(get("/v1/admin/fallback").cookie(admin)).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.routedToday").value(2)).andExpect(jsonPath("$.dailyCapUsd").value(0.20));
    }

    @Test
    void aRoutedLanguageAlwaysUsesTheFallbackForPaidPlans() throws Exception {
        assertThat(provider(made(paid, Map.of("kind", "song", "prompt", "english folk", "language", "en"))[0])).isEqualTo("lyria");
    }
}
