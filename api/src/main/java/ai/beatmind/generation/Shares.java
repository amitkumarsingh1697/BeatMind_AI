package ai.beatmind.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Share links (US-024, FEAT-13, REQ-SEC-03, TM-04). A link is a random 192-bit token; only its
 * SHA-256 is stored, so the full link is shown once, when it is made. Revoking a link, deleting
 * the song or a takedown stops it at once.
 */
@Service
class Shares {

    static final int MAX_ACTIVE_PER_SONG = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** A link as its owner sees it. {@code url} is set only in the response that created it. */
    record Share(UUID id, String visibility, int takeIndex, String hint, int views, OffsetDateTime createdAt,
                 String url) {}

    /** The song behind a live link. */
    record Shared(UUID shareId, UUID songId, String visibility, int takeIndex) {}

    private final JdbcClient jdbc;

    Shares(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    Share create(UUID songId, String visibility, int takeIndex, String locale) {
        String token = newToken();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO share_link (id, song_id, token_hash, token_hint, visibility, take_idx)
                VALUES (:id, :song, :hash, :hint, :visibility, :take)
                """)
                .param("id", id).param("song", songId).param("hash", hash(token))
                .param("hint", token.substring(token.length() - 4)).param("visibility", visibility)
                .param("take", takeIndex)
                .update();
        Share s = list(songId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        return new Share(s.id(), s.visibility(), s.takeIndex(), s.hint(), s.views(), s.createdAt(),
                "/" + locale + "/s/" + token);
    }

    List<Share> list(UUID songId) {
        return jdbc.sql("""
                SELECT id, visibility, take_idx, token_hint, views, created_at FROM share_link
                 WHERE song_id = :song AND revoked_at IS NULL ORDER BY created_at DESC
                """)
                .param("song", songId)
                .query((rs, n) -> new Share(rs.getObject("id", UUID.class), rs.getString("visibility"),
                        rs.getInt("take_idx"), rs.getString("token_hint"), rs.getInt("views"),
                        rs.getObject("created_at", OffsetDateTime.class), null))
                .list();
    }

    boolean revoke(UUID songId, UUID shareId) {
        return jdbc.sql("UPDATE share_link SET revoked_at = now() WHERE id = :id AND song_id = :song AND revoked_at IS NULL")
                .param("id", shareId).param("song", songId).update() == 1;
    }

    int revokeAll(UUID songId) {
        return jdbc.sql("UPDATE share_link SET revoked_at = now() WHERE song_id = :song AND revoked_at IS NULL")
                .param("song", songId).update();
    }

    /** The live link for a token: not revoked, and its song not deleted. */
    Optional<Shared> open(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,64}")) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT l.id, l.song_id, l.visibility, l.take_idx FROM share_link l JOIN song s ON s.id = l.song_id
                 WHERE l.token_hash = :hash AND l.revoked_at IS NULL AND s.deleted_at IS NULL
                """)
                .param("hash", hash(token))
                .query((rs, n) -> new Shared(rs.getObject("id", UUID.class), rs.getObject("song_id", UUID.class),
                        rs.getString("visibility"), rs.getInt("take_idx")))
                .optional();
    }

    void counted(UUID shareId) {
        jdbc.sql("UPDATE share_link SET views = views + 1 WHERE id = :id").param("id", shareId).update();
    }

    /** True if the token was a link that has since been revoked (the page then says so). */
    boolean wasRevoked(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,64}")) {
            return false;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM share_link WHERE token_hash = :hash AND revoked_at IS NOT NULL)")
                .param("hash", hash(token)).query(Boolean.class).single();
    }
}
