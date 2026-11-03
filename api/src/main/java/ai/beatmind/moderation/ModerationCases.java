package ai.beatmind.moderation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moderation cases (REQ-F-50 to REQ-F-52, REQ-F-59): every blocked request and withheld take is
 * recorded; the user can appeal it once, and an admin upholds or overturns it. The blocked text
 * itself is not kept, only its SHA-256 and the rule that matched (DPIA: data minimisation).
 */
@Service
public class ModerationCases {

    /** A case as admins and the owner see it. */
    public record Case(UUID id, String kind, UUID userId, UUID songId, Integer takeIdx, String category, String rule,
                       String field, String detail, String status, String appealNote, OffsetDateTime appealedAt,
                       String decisionNote, OffsetDateTime decidedAt, OffsetDateTime createdAt) {}

    /** Published after an admin decides, so the song module can release an overturned take. */
    public record CaseDecided(UUID caseId, String kind, UUID adminId, UUID userId, UUID songId, Integer takeIdx,
                              boolean overturned) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    ModerationCases(JdbcClient jdbc, JsonMapper json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
    }

    /** Records a blocked prompt, title or lyrics ({@code text_block}) or AI lyrics ({@code lyrics_block}). */
    public UUID recordTextBlock(UUID userId, String kind, TextModeration.Verdict verdict, String text) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO moderation_case (id, kind, user_id, category, rule, field, text_sha256)
                VALUES (:id, :kind, :user, :category, :rule, :field, :sha)
                """)
                .param("id", id).param("kind", kind).param("user", userId)
                .param("category", verdict.category()).param("rule", verdict.rule())
                .param("field", verdict.field()).param("sha", sha256(text == null ? "" : text))
                .update();
        return id;
    }

    /** Records a take withheld by the similarity screen (REQ-F-52). */
    public UUID recordSimilarity(UUID userId, UUID songId, int takeIdx, Map<String, Object> detail) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO moderation_case (id, kind, user_id, song_id, take_idx, category, rule, detail)
                VALUES (:id, 'similarity', :user, :song, :idx, 'similarity', 'fingerprint_match', CAST(:detail AS jsonb))
                """)
                .param("id", id).param("user", userId).param("song", songId).param("idx", takeIdx)
                .param("detail", json.writeValueAsString(detail))
                .update();
        return id;
    }

    /**
     * True if an admin overturned a block of exactly this text for this user, so the same request
     * goes through when they try again (REQ-F-59).
     */
    public boolean cleared(UUID userId, String text) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM moderation_case
                                WHERE user_id = :user AND text_sha256 = :sha AND status = 'overturned')
                """)
                .param("user", userId).param("sha", sha256(text == null ? "" : text))
                .query(Boolean.class).single();
    }

    /** The case for a withheld take, if any. */
    public Optional<UUID> forTake(UUID songId, int takeIdx) {
        return jdbc.sql("SELECT id FROM moderation_case WHERE song_id = :song AND take_idx = :idx ORDER BY created_at DESC LIMIT 1")
                .param("song", songId).param("idx", takeIdx)
                .query(UUID.class).optional();
    }

    /**
     * The owner asks for a human review (REQ-F-59, 72-hour target). Returns false if the case is not
     * theirs or was already appealed or decided.
     */
    public boolean appeal(UUID caseId, UUID userId, String note) {
        return jdbc.sql("""
                UPDATE moderation_case SET status = 'appealed', appeal_note = :note, appealed_at = now()
                 WHERE id = :id AND user_id = :user AND status = 'open'
                """)
                .param("id", caseId).param("user", userId).param("note", note == null ? null : note.strip())
                .update() == 1;
    }

    public List<Case> mine(UUID userId, int limit) {
        return jdbc.sql(SELECT + " WHERE user_id = :user ORDER BY created_at DESC LIMIT :limit")
                .param("user", userId).param("limit", limit).query(this::row).list();
    }

    /** The admin queue: appealed cases first (oldest first, they have a deadline), then the rest. */
    public List<Case> queue(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.sql(SELECT + """
                     WHERE status IN ('appealed', 'open')
                     ORDER BY status = 'appealed' DESC, CASE WHEN status = 'appealed' THEN appealed_at END, created_at DESC
                     LIMIT :limit
                    """).param("limit", limit).query(this::row).list();
        }
        return jdbc.sql(SELECT + " WHERE status = :status ORDER BY created_at DESC LIMIT :limit")
                .param("status", status).param("limit", limit).query(this::row).list();
    }

    public Optional<Case> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(this::row).optional();
    }

    /** An admin decides a case; every decision is audited. Returns the decided case. */
    @Transactional
    public Optional<Case> decide(UUID caseId, UUID adminId, boolean overturn, String note) {
        int changed = jdbc.sql("""
                UPDATE moderation_case
                   SET status = :status, decided_by = :admin, decided_at = now(), decision_note = :note
                 WHERE id = :id AND status IN ('open', 'appealed')
                """)
                .param("status", overturn ? "overturned" : "upheld").param("admin", adminId)
                .param("note", note == null ? null : note.strip()).param("id", caseId)
                .update();
        if (changed == 0) {
            return Optional.empty();
        }
        jdbc.sql("""
                INSERT INTO admin_audit (id, actor_user_id, action, target, detail)
                VALUES (:id, :actor, :action, :target, :detail)
                """)
                .param("id", UUID.randomUUID()).param("actor", adminId)
                .param("action", overturn ? "moderation_overturn" : "moderation_uphold")
                .param("target", caseId.toString()).param("detail", note)
                .update();
        Case decided = find(caseId).orElseThrow();
        events.publishEvent(new CaseDecided(decided.id(), decided.kind(), adminId, decided.userId(), decided.songId(),
                decided.takeIdx(), overturn));
        return Optional.of(decided);
    }

    private static final String SELECT = """
            SELECT id, kind, user_id, song_id, take_idx, category, rule, field, detail::text AS detail, status,
                   appeal_note, appealed_at, decision_note, decided_at, created_at
              FROM moderation_case
            """;

    private Case row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int idx = rs.getInt("take_idx");
        Integer takeIdx = rs.wasNull() ? null : idx;
        return new Case(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("user_id", UUID.class),
                rs.getObject("song_id", UUID.class), takeIdx, rs.getString("category"),
                rs.getString("rule"), rs.getString("field"), rs.getString("detail"), rs.getString("status"),
                rs.getString("appeal_note"), rs.getObject("appealed_at", OffsetDateTime.class),
                rs.getString("decision_note"), rs.getObject("decided_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
