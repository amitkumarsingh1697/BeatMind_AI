package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** US-004 dashboards and REQ-NFR-07 cost per song. */
@DatabaseTest
@Tag("TC-REQ-NFR-07-01")
class GenerationMetricsTest {

    @Autowired
    GenerationMetrics metrics;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        SignInHelper.reset(jdbc);
    }

    @Test
    void reportsQueueDepthTimeToPlayableAndCostPerSong() {
        // Two finished songs that took 9 s and 27 s of model time, one waiting, one dead.
        job("succeeded", "now() - interval '30 seconds'", "now()", "{\"seconds\": 9}");
        job("succeeded", "now() - interval '50 seconds'", "now()", "{\"seconds\": 27}");
        job("queued", "now() - interval '20 seconds'", "NULL", null);
        job("dead", "now() - interval '40 seconds'", "now()", null);

        metrics.refresh();

        assertThat(gauge("beatmind.queue.jobs", "status", "queued")).isEqualTo(1);
        assertThat(gauge("beatmind.queue.jobs", "status", "dead")).isEqualTo(1);
        assertThat(meters.get("beatmind.queue.oldest.waiting").gauge().value()).isBetween(19.0, 30.0);
        assertThat(gauge("beatmind.time.to.playable", "quantile", "0.95")).isBetween(45.0, 55.0);
        assertThat(gauge("beatmind.jobs.finished.last.hour", "outcome", "failed")).isEqualTo(1);
        assertThat(meters.get("beatmind.worker.busy.seconds.today").gauge().value()).isEqualTo(36.0);
        // 36 s of GPU at US$0.40 an hour over 2 songs = US$0.002 per song.
        assertThat(meters.get("beatmind.cost.per.song").gauge().value()).isCloseTo(0.002, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(meters.get("beatmind.cost.per.song.ceiling").gauge().value()).isEqualTo(0.012);
    }

    private void job(String status, String createdAt, String finishedAt, String result) {
        jdbc.sql("""
                INSERT INTO generation_job (id, kind, status, payload, created_at, available_at, finished_at, result)
                VALUES (gen_random_uuid(), 'song', :status, '{}', %1$s, %1$s, %2$s, CAST(:result AS jsonb))
                """.formatted(createdAt, finishedAt))
                .param("status", status)
                .param("result", result)
                .update();
    }

    private double gauge(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).gauge().value();
    }
}
