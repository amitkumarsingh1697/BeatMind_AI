package ai.beatmind.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads a user's generation jobs, with their song and takes. */
@Repository
class GenerationStore {

    private static final String COLUMNS = """
            j.id, j.kind, j.status, j.payload::text AS payload, j.created_at, j.finished_at, j.started_at,
            j.priority, j.available_at,
            j.finalized_at IS NOT NULL AS finalized, s.id AS song_id, s.title
              FROM generation_job j LEFT JOIN song s ON s.job_id = j.id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SongStore songs;
    private final Credits credits;

    /** Run time assumed before any job has finished (stub worker about 5 s, GPU about 40 s). */
    static final int DEFAULT_RUN_SECONDS = 40;

    /** What the queue looks like right now, for estimates (US-014). */
    record QueueStats(double averageRunSeconds, int workers) {}

    GenerationStore(JdbcClient jdbc, JsonMapper json, SongStore songs, Credits credits) {
        this.jdbc = jdbc;
        this.json = json;
        this.songs = songs;
        this.credits = credits;
    }

    /** The job, only if it belongs to {@code ownerId}; another user's job looks like a missing one. */
    Optional<Generation> find(UUID id, UUID ownerId) {
        return jdbc.sql("SELECT " + COLUMNS + " WHERE j.id = :id AND j.user_id = :owner")
                .param("id", id)
                .param("owner", ownerId)
                .query(this::map)
                .optional();
    }

    /** Song and beat requests, newest first. Lyric-writer jobs are not songs (US-018). */
    List<Generation> recent(UUID ownerId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " WHERE j.user_id = :owner AND j.kind <> 'lyrics'"
                        + " AND (s.id IS NULL OR s.deleted_at IS NULL) ORDER BY j.created_at DESC LIMIT :limit")
                .param("owner", ownerId)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /** The song made by job {@code id}, if it belongs to {@code ownerId}. */
    Optional<UUID> songOf(UUID id, UUID ownerId) {
        return jdbc.sql("SELECT s.id FROM song s JOIN generation_job j ON j.id = s.job_id WHERE j.id = :id AND j.user_id = :owner")
                .param("id", id)
                .param("owner", ownerId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Mean run time of the last 50 song and beat jobs finished in the past day, and the workers
     * seen claiming jobs in the last 10 minutes (at least one).
     */
    QueueStats stats() {
        Double avg = jdbc.sql("""
                SELECT avg(extract(epoch FROM finished_at - started_at)) FROM (
                    SELECT finished_at, started_at FROM generation_job
                     WHERE status = 'succeeded' AND kind IN ('song', 'beat') AND provider = 'self' AND started_at IS NOT NULL
                       AND finished_at > now() - interval '1 day'
                     ORDER BY finished_at DESC LIMIT 50) recent
                """).query(Double.class).optional().orElse(null);
        Integer workers = jdbc.sql("""
                SELECT count(DISTINCT locked_by) FROM generation_job
                 WHERE locked_by IS NOT NULL AND provider = 'self' AND locked_at > now() - interval '10 minutes'
                """).query(Integer.class).single();
        return new QueueStats(avg == null ? DEFAULT_RUN_SECONDS : Math.max(1, avg), Math.max(1, workers));
    }

    /** Jobs that start before this one: higher priority, or same priority and earlier in line. */
    int ahead(UUID id, int priority, OffsetDateTime availableAt, OffsetDateTime createdAt) {
        return jdbc.sql("""
                SELECT count(*) FROM generation_job
                 WHERE status IN ('queued', 'failed') AND kind IN ('song', 'beat') AND provider = 'self' AND id <> :id
                   AND (priority > :priority
                        OR (priority = :priority AND (available_at, created_at) < (:available, :created)))
                """)
                .param("id", id).param("priority", priority).param("available", availableAt).param("created", createdAt)
                .query(Integer.class).single();
    }

    /** Where a new job of this priority would join the GPU queue (1 is next). US-016 routing. */
    int positionForNew(int priority) {
        return 1 + jdbc.sql("""
                SELECT count(*) FROM generation_job
                 WHERE status IN ('queued', 'failed') AND kind IN ('song', 'beat') AND provider = 'self'
                   AND priority >= :priority
                """).param("priority", priority).query(Integer.class).single();
    }

    /**
     * Expected seconds until a job's takes are ready. A waiting job needs every job ahead of it to
     * clear the workers, then its own run; a running job needs what is left of a typical run.
     */
    static int estimate(QueueStats stats, int position, long runningForSeconds) {
        double run = stats.averageRunSeconds();
        if (position <= 0) {
            return (int) Math.max(5, Math.round(run - runningForSeconds));
        }
        double rounds = Math.floor((position - 1) / (double) stats.workers());
        // On average, the job ahead on a busy worker is half done.
        double wait = rounds * run + run / 2 + run;
        return (int) Math.round(wait);
    }

    private Generation map(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID songId = rs.getObject("song_id", UUID.class);
        boolean finalized = rs.getBoolean("finalized");
        String status = Generation.publicStatus(rs.getString("status"));
        if (("succeeded".equals(status) || "failed".equals(status)) && !finalized && songId != null) {
            status = "running";
        }
        JsonNode payload = json.readTree(rs.getString("payload"));
        Integer position = null;
        Integer eta = null;
        if ("queued".equals(status) || "running".equals(status)) {
            QueueStats stats = stats();
            OffsetDateTime started = rs.getObject("started_at", OffsetDateTime.class);
            boolean running = "running".equals(rs.getString("status"));
            if (running) {
                position = 0;
                long elapsed = started == null ? 0 : Math.max(0, java.time.Duration.between(started, OffsetDateTime.now()).toSeconds());
                eta = estimate(stats, 0, elapsed);
            } else if ("queued".equals(status) && !"succeeded".equals(rs.getString("status"))) {
                position = 1 + ahead(id, rs.getInt("priority"), rs.getObject("available_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class));
                eta = estimate(stats, position, 0);
            }
        }
        List<Generation.Take> takes = songId == null || !"succeeded".equals(status) ? List.of()
                : songs.takes(songId).stream()
                        .map(t -> new Generation.Take(t.index(), t.status(),
                                "ready".equals(t.status()) ? SongViews.audioUrl(songId, t.index()) : null,
                                SongViews.previewFormat(t)))
                        .toList();
        if ("succeeded".equals(status) && takes.stream().noneMatch(t -> "ready".equals(t.status())) && songId != null) {
            status = "failed";
        }
        return new Generation(
                id,
                songId,
                rs.getString("kind"),
                status,
                rs.getString("title"),
                payload.path("prompt").asString(""),
                payload.path("language").asString(null),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                takes,
                finalized ? credits.refunded(id) : 0,
                "failed".equals(status) ? "We could not make this song. Your credits have been refunded." : null,
                position,
                eta);
    }
}
