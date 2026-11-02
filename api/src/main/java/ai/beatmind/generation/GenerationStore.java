package ai.beatmind.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads a user's generation jobs from the queue table. */
@Repository
class GenerationStore {

    private static final String COLUMNS = """
            id, kind, status, payload::text AS payload, result::text AS result, created_at, finished_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    GenerationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The job, only if it belongs to {@code ownerId}; another user's job looks like a missing one. */
    Optional<Generation> find(UUID id, UUID ownerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM generation_job WHERE id = :id AND user_id = :owner")
                .param("id", id)
                .param("owner", ownerId)
                .query(this::map)
                .optional();
    }

    List<Generation> recent(UUID ownerId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM generation_job WHERE user_id = :owner ORDER BY created_at DESC LIMIT :limit")
                .param("owner", ownerId)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    long active(UUID ownerId) {
        return jdbc.sql("SELECT count(*) FROM generation_job WHERE user_id = :owner AND status IN ('queued', 'running')")
                .param("owner", ownerId)
                .query(Long.class)
                .single();
    }

    /** The storage key of take {@code index} (1-based), if the job finished and has it. */
    Optional<String> takeKey(UUID id, UUID ownerId, int index) {
        return jdbc.sql("""
                SELECT result -> 'takes' -> (:index - 1) ->> 'key' FROM generation_job
                 WHERE id = :id AND user_id = :owner AND status = 'succeeded'
                """)
                .param("index", index)
                .param("id", id)
                .param("owner", ownerId)
                .query(String.class)
                .optional();
    }

    private Generation map(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String status = Generation.publicStatus(rs.getString("status"));
        JsonNode payload = json.readTree(rs.getString("payload"));
        List<Generation.Take> takes = new ArrayList<>();
        String result = rs.getString("result");
        if ("succeeded".equals(status) && result != null) {
            JsonNode list = json.readTree(result).path("takes");
            for (int i = 0; i < list.size(); i++) {
                takes.add(new Generation.Take(i + 1,
                        "/v1/generations/" + id + "/takes/" + (i + 1) + "/audio",
                        list.get(i).path("format").asString("wav")));
            }
        }
        return new Generation(
                id,
                rs.getString("kind"),
                status,
                payload.path("prompt").asString(""),
                payload.path("language").asString(null),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                takes,
                "failed".equals(status) ? "Something went wrong making this song. Please try again." : null);
    }
}
