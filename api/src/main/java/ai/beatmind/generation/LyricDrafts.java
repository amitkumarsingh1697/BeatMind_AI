package ai.beatmind.generation;

import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.moderation.TextModeration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AI lyric writer (US-018, REQ-F-03): a worker writes sectioned lyrics from a topic and mood
 * in the chosen language; the API screens what it wrote before the creator sees it (TM-06).
 */
@Service
class LyricDrafts {

    static final int MAX_TOPIC_GRAPHEMES = 200;
    static final int MAX_MOOD_GRAPHEMES = 100;

    record Draft(UUID id, UUID userId, String topic, String mood, String language, String status, String lyrics,
                 String model, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final JobQueue queue;
    private final JsonMapper json;
    private final Entitlements entitlements;
    private final TextModeration moderation;
    private final ModerationCases cases;

    LyricDrafts(JdbcClient jdbc, JobQueue queue, JsonMapper json, Entitlements entitlements, TextModeration moderation,
                ModerationCases cases) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.json = json;
        this.entitlements = entitlements;
        this.moderation = moderation;
        this.cases = cases;
    }

    /** Drafts this account asked for since midnight India time. */
    long today(UUID userId) {
        OffsetDateTime midnight = ZonedDateTime.now(entitlements.dayZone()).toLocalDate()
                .atStartOfDay(entitlements.dayZone()).toOffsetDateTime();
        return jdbc.sql("SELECT count(*) FROM lyric_draft WHERE user_id = :user AND created_at >= :since")
                .param("user", userId).param("since", midnight).query(Long.class).single();
    }

    /** Queues a draft. Lyric jobs jump the song queue: they are short and the creator is waiting. */
    @Transactional
    Draft start(UUID userId, String topic, String mood, String language) {
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftId", id.toString());
        payload.put("topic", topic);
        payload.put("mood", mood);
        payload.put("language", language);
        payload.put("languageTag", TextRules.languageTag(language));
        UUID jobId = queue.enqueue(JobQueue.Kind.LYRICS, json.writeValueAsString(payload), 5, userId);
        jdbc.sql("""
                INSERT INTO lyric_draft (id, user_id, job_id, topic, mood, language)
                VALUES (:id, :user, :job, :topic, :mood, :language)
                """)
                .param("id", id).param("user", userId).param("job", jobId)
                .param("topic", topic).param("mood", mood).param("language", language)
                .update();
        return find(id, userId).orElseThrow();
    }

    Optional<Draft> find(UUID id, UUID userId) {
        return jdbc.sql("""
                SELECT id, user_id, topic, mood, language, status, lyrics, model, created_at
                  FROM lyric_draft WHERE id = :id AND user_id = :user
                """)
                .param("id", id).param("user", userId)
                .query((rs, n) -> new Draft(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("topic"), rs.getString("mood"), rs.getString("language"), rs.getString("status"),
                        rs.getString("lyrics"), rs.getString("model"), rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    /**
     * Called by the finalizer for a finished lyrics job: stores the lyrics if they pass moderation.
     * Returns false if the job has no draft.
     */
    boolean finish(UUID jobId, UUID userId, String status, String result) {
        Optional<UUID> draftId = jdbc.sql("SELECT id FROM lyric_draft WHERE job_id = :job")
                .param("job", jobId).query(UUID.class).optional();
        if (draftId.isEmpty()) {
            return false;
        }
        String newStatus = "failed";
        String lyrics = null;
        String model = null;
        if ("succeeded".equals(status) && result != null) {
            JsonNode node = json.readTree(result);
            lyrics = TextRules.nfc(node.path("lyrics").asString("").strip());
            model = node.path("model").asString(null);
            if (lyrics.isEmpty()) {
                lyrics = null;
            } else {
                if (TextRules.graphemes(lyrics) > SongRequests.MAX_LYRICS_GRAPHEMES) {
                    lyrics = TextRules.truncate(lyrics, SongRequests.MAX_LYRICS_GRAPHEMES);
                }
                TextModeration.Verdict verdict = moderation.check("lyrics", lyrics);
                if (verdict.blocked()) {
                    cases.recordTextBlock(userId, "lyrics_block", verdict, lyrics);
                    newStatus = "blocked";
                    lyrics = null;
                } else {
                    newStatus = "ready";
                }
            }
        }
        jdbc.sql("UPDATE lyric_draft SET status = :status, lyrics = :lyrics, model = :model, updated_at = now() WHERE id = :id")
                .param("status", newStatus).param("lyrics", lyrics).param("model", model).param("id", draftId.get())
                .update();
        return true;
    }
}
