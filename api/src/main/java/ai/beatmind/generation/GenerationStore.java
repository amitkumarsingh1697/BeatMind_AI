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
            j.id, j.kind, j.status, j.payload::text AS payload, j.created_at, j.finished_at,
            j.finalized_at IS NOT NULL AS finalized, s.id AS song_id, s.title
              FROM generation_job j LEFT JOIN song s ON s.job_id = j.id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SongStore songs;
    private final Credits credits;

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

    List<Generation> recent(UUID ownerId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " WHERE j.user_id = :owner AND (s.id IS NULL OR s.deleted_at IS NULL) ORDER BY j.created_at DESC LIMIT :limit")
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

    private Generation map(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID songId = rs.getObject("song_id", UUID.class);
        boolean finalized = rs.getBoolean("finalized");
        String status = Generation.publicStatus(rs.getString("status"));
        if (("succeeded".equals(status) || "failed".equals(status)) && !finalized && songId != null) {
            status = "running";
        }
        JsonNode payload = json.readTree(rs.getString("payload"));
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
                "failed".equals(status) ? "We could not make this song. Your credits have been refunded." : null);
    }
}
