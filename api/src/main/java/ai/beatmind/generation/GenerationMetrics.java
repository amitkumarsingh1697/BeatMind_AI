package ai.beatmind.generation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pipeline metrics for the SLO dashboards (US-004, REQ-NFR-07), read from the queue table every
 * 15 seconds. The table already holds what workers did, so workers need no metrics endpoint.
 *
 * <ul>
 *   <li>{@code beatmind_queue_jobs{status}}: jobs per queue state (queue depth)</li>
 *   <li>{@code beatmind_queue_oldest_waiting_seconds}: age of the oldest job still waiting</li>
 *   <li>{@code beatmind_time_to_playable_seconds{quantile}}: request to takes ready, last hour (REQ-F-01)</li>
 *   <li>{@code beatmind_jobs_finished_last_hour{outcome}}: succeeded and failed jobs</li>
 *   <li>{@code beatmind_worker_busy_seconds_today}: model time spent today (GPU use)</li>
 *   <li>{@code beatmind_cost_per_song_usd}: today's model time times the GPU price, per song</li>
 *   <li>{@code beatmind_cost_per_song_ceiling_usd}: the ceiling from the financial model</li>
 * </ul>
 */
@Component
class GenerationMetrics {

    private static final List<String> STATUSES = List.of("queued", "running", "succeeded", "failed", "dead");

    private final JdbcClient jdbc;
    private final double gpuUsdPerHour;
    private final MultiGauge queueDepth;
    private final MultiGauge timeToPlayable;
    private final MultiGauge finished;
    private final AtomicReference<Double> oldestWaiting = new AtomicReference<>(0.0);
    private final AtomicReference<Double> busySecondsToday = new AtomicReference<>(0.0);
    private final AtomicReference<Double> costPerSong = new AtomicReference<>(0.0);

    GenerationMetrics(JdbcClient jdbc, MeterRegistry meters,
                      @Value("${beatmind.cost.gpu-usd-per-hour:0.40}") double gpuUsdPerHour,
                      @Value("${beatmind.cost.ceiling-usd-per-song:0.012}") double ceilingUsdPerSong) {
        this.jdbc = jdbc;
        this.gpuUsdPerHour = gpuUsdPerHour;
        this.queueDepth = MultiGauge.builder("beatmind.queue.jobs").description("Jobs per queue state").register(meters);
        this.timeToPlayable = MultiGauge.builder("beatmind.time.to.playable").baseUnit("seconds")
                .description("Time from request to takes ready, last hour").register(meters);
        this.finished = MultiGauge.builder("beatmind.jobs.finished.last.hour")
                .description("Jobs finished in the last hour by outcome").register(meters);
        Gauge.builder("beatmind.queue.oldest.waiting", oldestWaiting, AtomicReference::get).baseUnit("seconds")
                .description("Age of the oldest job waiting to start").register(meters);
        Gauge.builder("beatmind.worker.busy.seconds.today", busySecondsToday, AtomicReference::get)
                .description("Model seconds spent today (UTC)").register(meters);
        Gauge.builder("beatmind.cost.per.song", costPerSong, AtomicReference::get).baseUnit("usd")
                .description("Average model cost per finished song today (UTC)").register(meters);
        Gauge.builder("beatmind.cost.per.song.ceiling", () -> ceilingUsdPerSong).baseUnit("usd")
                .description("Cost per song ceiling from the financial model").register(meters);
    }

    @Scheduled(fixedDelayString = "${beatmind.metrics.refresh:15s}", initialDelayString = "5s")
    void refresh() {
        Map<String, Long> counts = jdbc.sql("SELECT status, count(*) FROM generation_job GROUP BY status")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        queueDepth.register(STATUSES.stream()
                .map(s -> MultiGauge.Row.of(Tags.of("status", s), counts.getOrDefault(s, 0L)))
                .toList(), true);

        oldestWaiting.set(jdbc.sql("""
                SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)::float8
                  FROM generation_job WHERE status = 'queued' AND available_at <= now()
                """).query(Double.class).single());

        var quantiles = jdbc.sql("""
                SELECT coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at - created_at)), 0)::float8,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at - created_at)), 0)::float8
                  FROM generation_job
                 WHERE status = 'succeeded' AND finished_at > now() - interval '1 hour'
                """).query((rs, n) -> new double[] {rs.getDouble(1), rs.getDouble(2)}).single();
        timeToPlayable.register(List.of(
                MultiGauge.Row.of(Tags.of("quantile", "0.5"), quantiles[0]),
                MultiGauge.Row.of(Tags.of("quantile", "0.95"), quantiles[1])), true);

        var outcomes = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'succeeded'), count(*) FILTER (WHERE status IN ('dead', 'failed'))
                  FROM generation_job WHERE finished_at > now() - interval '1 hour'
                """).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        finished.register(List.of(
                MultiGauge.Row.of(Tags.of("outcome", "succeeded"), outcomes[0]),
                MultiGauge.Row.of(Tags.of("outcome", "failed"), outcomes[1])), true);

        var today = jdbc.sql("""
                SELECT coalesce(sum((result ->> 'seconds')::float8), 0)::float8, count(*)
                  FROM generation_job
                 WHERE status = 'succeeded' AND finished_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """).query((rs, n) -> new double[] {rs.getDouble(1), rs.getLong(2)}).single();
        busySecondsToday.set(today[0]);
        costPerSong.set(today[1] == 0 ? 0.0 : today[0] * gpuUsdPerHour / 3600.0 / today[1]);
    }
}
