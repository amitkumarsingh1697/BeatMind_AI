package ai.beatmind.generation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * US-016, ADR-007, TDD 9: paid songs go to the Lyria API when our GPU queue would keep them waiting
 * too long, or always for languages routed there, until the day's spend cap is reached. Free songs
 * never use it. The cap is checked under a lock, so two requests cannot both take the last dollar.
 * Spend counts the estimate until the worker reports the actual cost.
 */
@Service
class Fallback {

    private static final Logger log = LoggerFactory.getLogger(Fallback.class);

    @ConfigurationProperties("beatmind.fallback")
    record Settings(boolean enabled, int afterWaitSeconds, List<String> languages, BigDecimal usdPerTake,
                    BigDecimal dailyCapUsd, ZoneId dayZone) {

        Settings {
            afterWaitSeconds = afterWaitSeconds <= 0 ? 120 : afterWaitSeconds;
            languages = languages == null ? List.of()
                    : languages.stream().map(l -> l.trim().toLowerCase(Locale.ROOT)).filter(l -> !l.isEmpty()).toList();
            usdPerTake = usdPerTake == null ? new BigDecimal("0.04") : usdPerTake;
            dailyCapUsd = dailyCapUsd == null ? BigDecimal.TEN : dailyCapUsd;
            dayZone = dayZone == null ? ZoneId.of("Asia/Kolkata") : dayZone;
        }
    }

    /** Where a job runs, why, and what it is expected to cost. */
    record Route(String provider, String reason, BigDecimal costUsd) {
        static final Route SELF = new Route("self", null, null);
    }

    record Status(boolean enabled, BigDecimal spentTodayUsd, BigDecimal dailyCapUsd, int routedToday) {}

    private final JdbcClient jdbc;
    private final GenerationStore store;
    private final Settings settings;
    private final Counter routed;
    private final Counter capped;

    Fallback(JdbcClient jdbc, GenerationStore store, Settings settings, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.store = store;
        this.settings = settings;
        this.routed = Counter.builder("beatmind.fallback.routed").description("Songs sent to the paid fallback API")
                .register(meters);
        this.capped = Counter.builder("beatmind.fallback.capped")
                .description("Songs that would have used the fallback but the daily spend cap was reached").register(meters);
        Gauge.builder("beatmind.fallback.spend.today.usd", this, f -> f.spentToday().doubleValue())
                .description("Fallback API spend today, estimated until actual costs arrive").register(meters);
    }

    /** Call inside the request's transaction, before the job is queued. */
    Route route(String plan, String language, int priority, int takes) {
        if (!settings.enabled() || !Entitlements.paid(plan)) {
            return Route.SELF;
        }
        String reason;
        if (settings.languages().contains(language == null ? "" : language.toLowerCase(Locale.ROOT))) {
            reason = "language";
        } else {
            int wait = GenerationStore.estimate(store.stats(), store.positionForNew(priority), 0);
            if (wait <= settings.afterWaitSeconds()) {
                return Route.SELF;
            }
            reason = "queue_wait_" + wait + "s";
        }
        BigDecimal cost = settings.usdPerTake().multiply(BigDecimal.valueOf(takes));
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('beatmind-fallback-spend'))").query().singleRow();
        BigDecimal spent = spentToday();
        if (spent.add(cost).compareTo(settings.dailyCapUsd()) > 0) {
            capped.increment();
            log.warn("fallback spend cap reached: {} of {} USD today; {} stays on the GPU queue", spent,
                    settings.dailyCapUsd(), reason);
            return Route.SELF;
        }
        routed.increment();
        return new Route("lyria", reason, cost);
    }

    BigDecimal spentToday() {
        return jdbc.sql("""
                SELECT coalesce(sum(coalesce(cost_actual_usd, cost_estimate_usd)), 0) FROM generation_job
                 WHERE provider = 'lyria' AND created_at >= :start
                """).param("start", LocalDate.now(settings.dayZone()).atStartOfDay(settings.dayZone()).toOffsetDateTime())
                .query(BigDecimal.class).single();
    }

    Status status() {
        int count = jdbc.sql("""
                SELECT count(*) FROM generation_job WHERE provider = 'lyria' AND created_at >= :start
                """).param("start", LocalDate.now(settings.dayZone()).atStartOfDay(settings.dayZone()).toOffsetDateTime())
                .query(Integer.class).single();
        return new Status(settings.enabled(), spentToday(), settings.dailyCapUsd(), count);
    }
}
