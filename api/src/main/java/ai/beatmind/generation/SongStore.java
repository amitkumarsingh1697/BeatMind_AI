package ai.beatmind.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Songs and their takes (TDD 5.1). Every read is scoped to the owner; deleted songs are hidden. */
@Repository
class SongStore {

    /** A new song as the creator asked for it. Text is already NFC. */
    record NewSong(UUID id, UUID ownerId, UUID jobId, String mode, String title, String languageTag, String prompt,
                   String lyrics, String vocalType, boolean instrumental, int durationSeconds, String plan,
                   String clientIpHash) {}

    record Song(UUID id, UUID ownerId, UUID jobId, String mode, String title, String titleLatin, String languageTag,
                String prompt, String lyrics, String vocalType, boolean instrumental, int durationSeconds,
                String planAtCreation, String rightsStatus, String jobStatus, boolean finalized,
                OffsetDateTime createdAt) {

        /** Public status: a finished job counts as succeeded only once its takes are recorded. */
        String status() {
            String s = Generation.publicStatus(jobStatus == null ? "succeeded" : jobStatus);
            if ("succeeded".equals(s) && !finalized) {
                return "running";
            }
            if ("failed".equals(s) && !finalized) {
                return "running";
            }
            return s;
        }
    }

    record Take(UUID id, int index, String status, Long watermarkId, Map<String, Asset> assets) {}

    record Asset(String key, String contentType, String sha256, long bytes) {}

    private static final String SONG_COLUMNS = """
            s.id, s.owner_id, s.job_id, s.mode, s.title, s.title_latin, s.language_tag, s.prompt, s.lyrics,
            s.vocal_type, s.instrumental, s.duration_seconds, s.plan_at_creation, s.rights_status, s.created_at,
            j.status AS job_status, (j.id IS NULL OR j.finalized_at IS NOT NULL) AS finalized
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    SongStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void create(NewSong s) {
        String rights = Entitlements.paid(s.plan()) ? "user_owned" : "bm_owned";
        jdbc.sql("""
                INSERT INTO song (id, owner_id, job_id, mode, title, title_latin, language_tag, prompt, lyrics,
                                  vocal_type, instrumental, duration_seconds, plan_at_creation, rights_status,
                                  search_text, search_key, client_ip_hash)
                VALUES (:id, :owner, :job, :mode, :title, :latin, :tag, :prompt, :lyrics, :vocal, :instrumental,
                        :duration, :plan, :rights, :text, :key, :ip)
                """)
                .param("id", s.id())
                .param("owner", s.ownerId())
                .param("job", s.jobId())
                .param("mode", s.mode())
                .param("title", s.title())
                .param("latin", TextRules.latin(s.title()))
                .param("tag", s.languageTag())
                .param("prompt", s.prompt())
                .param("lyrics", s.lyrics())
                .param("vocal", s.vocalType())
                .param("instrumental", s.instrumental())
                .param("duration", s.durationSeconds())
                .param("plan", s.plan())
                .param("rights", rights)
                .param("text", TextRules.searchText(s.title(), s.prompt(), s.lyrics()))
                .param("key", TextRules.searchKey(s.title() + " " + s.prompt() + " " + (s.lyrics() == null ? "" : s.lyrics())))
                .param("ip", s.clientIpHash())
                .update();
    }

    Optional<Song> find(UUID id, UUID ownerId) {
        return jdbc.sql("SELECT " + SONG_COLUMNS + """
                  FROM song s LEFT JOIN generation_job j ON j.id = s.job_id
                 WHERE s.id = :id AND s.owner_id = :owner AND s.deleted_at IS NULL
                """)
                .param("id", id)
                .param("owner", ownerId)
                .query(SongStore::mapSong)
                .optional();
    }

    Optional<Song> byJob(UUID jobId) {
        return jdbc.sql("SELECT " + SONG_COLUMNS + " FROM song s LEFT JOIN generation_job j ON j.id = s.job_id WHERE s.job_id = :job")
                .param("job", jobId)
                .query(SongStore::mapSong)
                .optional();
    }

    /**
     * The owner's songs, newest first. {@code query} matches any script as typed (lower-case NFC)
     * or by the loose Latin key, so Hinglish typing finds Devanagari titles. {@code before} pages.
     */
    List<Song> list(UUID ownerId, String query, OffsetDateTime before, int limit) {
        StringBuilder where = new StringBuilder("s.owner_id = :owner AND s.deleted_at IS NULL");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("owner", ownerId);
        params.put("limit", limit);
        if (before != null) {
            where.append(" AND s.created_at < :before");
            params.put("before", before);
        }
        if (query != null && !query.isBlank()) {
            String text = TextRules.searchText(query).strip();
            String key = TextRules.searchKey(query);
            where.append(" AND (strpos(s.search_text, :text) > 0");
            params.put("text", text);
            if (!key.isBlank()) {
                where.append(" OR strpos(s.search_key, :key) > 0");
                params.put("key", key);
            }
            where.append(")");
        }
        var sql = jdbc.sql("SELECT " + SONG_COLUMNS + " FROM song s LEFT JOIN generation_job j ON j.id = s.job_id WHERE "
                + where + " ORDER BY s.created_at DESC LIMIT :limit");
        for (var e : params.entrySet()) {
            sql = sql.param(e.getKey(), e.getValue());
        }
        return sql.query(SongStore::mapSong).list();
    }

    boolean rename(UUID id, UUID ownerId, String title) {
        Song song = find(id, ownerId).orElse(null);
        if (song == null) {
            return false;
        }
        jdbc.sql("""
                UPDATE song SET title = :title, title_latin = :latin, search_text = :text, search_key = :key, updated_at = now()
                 WHERE id = :id AND owner_id = :owner AND deleted_at IS NULL
                """)
                .param("title", title)
                .param("latin", TextRules.latin(title))
                .param("text", TextRules.searchText(title, song.prompt(), song.lyrics()))
                .param("key", TextRules.searchKey(title + " " + song.prompt() + " " + (song.lyrics() == null ? "" : song.lyrics())))
                .param("id", id)
                .param("owner", ownerId)
                .update();
        return true;
    }

    boolean softDelete(UUID id, UUID ownerId) {
        return jdbc.sql("UPDATE song SET deleted_at = now() WHERE id = :id AND owner_id = :owner AND deleted_at IS NULL")
                .param("id", id)
                .param("owner", ownerId)
                .update() == 1;
    }

    List<Take> takes(UUID songId) {
        return jdbc.sql("SELECT id, idx, status, watermark_id, assets::text AS assets FROM take WHERE song_id = :song ORDER BY idx")
                .param("song", songId)
                .query(this::mapTake)
                .list();
    }

    Map<UUID, List<Take>> takes(List<UUID> songIds) {
        Map<UUID, List<Take>> out = new LinkedHashMap<>();
        if (songIds.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT song_id, id, idx, status, watermark_id, assets::text AS assets FROM take WHERE song_id IN (:songs) ORDER BY idx")
                .param("songs", songIds)
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getObject("song_id", UUID.class), k -> new ArrayList<>()).add(mapTake(rs, n));
                    return null;
                })
                .list();
        return out;
    }

    Optional<Take> take(UUID songId, int index) {
        return takes(songId).stream().filter(t -> t.index() == index).findFirst();
    }

    /** The Latin title of a song that is not deleted, for download file names. */
    Optional<String> title(UUID id) {
        return jdbc.sql("SELECT title_latin FROM song WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    /** Paused by an admin (US-031): no new songs or lyrics until unpaused. */
    boolean suspended(UUID userId) {
        return jdbc.sql("SELECT suspended_at IS NOT NULL FROM app_user WHERE id = :id")
                .param("id", userId).query(Boolean.class).optional().orElse(false);
    }

    /** Songs this IP address asked for in the last hour, across all accounts (TM-12). */
    long recentFromIp(String ipHash) {
        return jdbc.sql("SELECT count(*) FROM song WHERE client_ip_hash = :ip AND created_at > now() - interval '1 hour'")
                .param("ip", ipHash)
                .query(Long.class)
                .single();
    }

    /** Songs this account asked for in the last 10 minutes. */
    long recentFromUser(UUID ownerId) {
        return jdbc.sql("SELECT count(*) FROM song WHERE owner_id = :owner AND created_at > now() - interval '10 minutes'")
                .param("owner", ownerId)
                .query(Long.class)
                .single();
    }

    /** Songs in progress: queued or running, or finished but not yet recorded. */
    long active(UUID ownerId) {
        return jdbc.sql("""
                SELECT count(*) FROM song s JOIN generation_job j ON j.id = s.job_id
                 WHERE s.owner_id = :owner AND j.finalized_at IS NULL
                """)
                .param("owner", ownerId)
                .query(Long.class)
                .single();
    }

    private static Song mapSong(ResultSet rs, int n) throws SQLException {
        return new Song(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getString("mode"),
                rs.getString("title"),
                rs.getString("title_latin"),
                rs.getString("language_tag"),
                rs.getString("prompt"),
                rs.getString("lyrics"),
                rs.getString("vocal_type"),
                rs.getBoolean("instrumental"),
                rs.getInt("duration_seconds"),
                rs.getString("plan_at_creation"),
                rs.getString("rights_status"),
                rs.getString("job_status"),
                rs.getBoolean("finalized"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private Take mapTake(ResultSet rs, int n) throws SQLException {
        Map<String, Asset> assets = new LinkedHashMap<>();
        JsonNode node = json.readTree(rs.getString("assets"));
        for (var e : node.properties()) {
            JsonNode a = e.getValue();
            assets.put(e.getKey(), new Asset(a.path("key").asString(""), a.path("contentType").asString("application/octet-stream"),
                    a.path("sha256").asString(null), a.path("bytes").asLong(-1)));
        }
        long wm = rs.getLong("watermark_id");
        return new Take(rs.getObject("id", UUID.class), rs.getInt("idx"), rs.getString("status"),
                rs.wasNull() ? null : wm, assets);
    }
}
