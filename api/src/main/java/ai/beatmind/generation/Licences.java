package ai.beatmind.generation;

import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Licences and rights certificates (US-025, REQ-F-21, REQ-F-22, TDD 6.3). A free song can be
 * licensed for commercial use, up to 5 a calendar month (India time), with attribution; a song
 * made on a paid plan gets an ownership certificate. Certificates never claim copyright
 * (REQ-F-26); the wording follows the rights and licence terms, section 6.
 */
@Service
class Licences {

    static final String ATTRIBUTION = "Music: made with BeatMind AI";
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    record Certificate(String id, UUID songId, UUID licenseeId, String scope, String title, String titleLatin,
                       String languageTag, String planAtCreation, OffsetDateTime songCreatedAt, boolean humanLyrics,
                       String masterSha256, OffsetDateTime issuedAt, OffsetDateTime revokedAt) {}

    record Quota(int used, int limit, String month) {}

    private final JdbcClient jdbc;
    private final SongStore songs;
    private final Credits credits;
    private final ProvenanceLedger ledger;
    private final Entitlements entitlements;

    Licences(JdbcClient jdbc, SongStore songs, Credits credits, ProvenanceLedger ledger, Entitlements entitlements) {
        this.jdbc = jdbc;
        this.songs = songs;
        this.credits = credits;
        this.ledger = ledger;
        this.entitlements = entitlements;
    }

    String month() {
        return ZonedDateTime.now(entitlements.dayZone()).format(MONTH);
    }

    Quota quota(UUID userId) {
        String month = month();
        int used = jdbc.sql("""
                SELECT count(*) FROM certificate
                 WHERE licensee_user_id = :user AND scope = 'commercial_free' AND quota_month = :month
                """)
                .param("user", userId).param("month", month).query(Integer.class).single();
        return new Quota(used, entitlements.freeCommercialLicencesPerMonth(), month);
    }

    /**
     * Licences a free song for commercial use, or issues the ownership certificate of a paid song.
     * Asking again returns the same certificate and does not use another licence.
     */
    @Transactional
    Certificate issue(UUID userId, UUID songId) {
        credits.lock(userId);
        SongStore.Song song = songs.find(songId, userId)
                .orElseThrow(() -> new SongRequests.Refusal(HttpStatus.NOT_FOUND, "not_found", "Song not found."));
        String scope = switch (song.rightsStatus()) {
            case "user_owned" -> "ownership_paid";
            case "bm_owned", "free_licensed" -> "commercial_free";
            default -> throw new SongRequests.Refusal(HttpStatus.FORBIDDEN, "disabled", "This song is unavailable.");
        };
        Optional<Certificate> existing = forSong(songId, scope);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<SongStore.Take> ready = songs.takes(songId).stream().filter(t -> "ready".equals(t.status())).toList();
        if (ready.isEmpty()) {
            throw new SongRequests.Refusal(HttpStatus.CONFLICT, "not_ready",
                    "This song has no finished take yet, so it cannot be licensed.");
        }
        String month = null;
        if ("commercial_free".equals(scope)) {
            Quota q = quota(userId);
            if (q.used() >= q.limit()) {
                throw new SongRequests.Refusal(HttpStatus.TOO_MANY_REQUESTS, "licence_quota",
                        "You have used this month's " + q.limit() + " commercial licences. They renew on the 1st, "
                                + "or upgrade to own every song you make.");
            }
            month = q.month();
        }
        String id = newId();
        jdbc.sql("""
                INSERT INTO certificate (id, song_id, licensee_user_id, scope, title, title_latin, language_tag,
                                         plan_at_creation, song_created_at, human_lyrics, master_sha256, quota_month)
                VALUES (:id, :song, :user, :scope, :title, :latin, :tag, :plan, :created, :human, :sha, :month)
                """)
                .param("id", id).param("song", songId).param("user", userId).param("scope", scope)
                .param("title", song.title()).param("latin", song.titleLatin()).param("tag", song.languageTag())
                .param("plan", song.planAtCreation()).param("created", song.createdAt())
                .param("human", humanLyrics(songId)).param("sha", masterSha(ready.getFirst()))
                .param("month", month)
                .update();
        if ("commercial_free".equals(scope)) {
            jdbc.sql("UPDATE song SET rights_status = 'free_licensed', updated_at = now() WHERE id = :id AND rights_status = 'bm_owned'")
                    .param("id", songId).update();
        }
        ledger.append(songId, "commercial_free".equals(scope) ? "licence_issued" : "ownership_certified", Actor.HUMAN,
                Map.of("certificateId", id, "scope", scope));
        return forSong(songId, scope).orElseThrow();
    }

    Optional<Certificate> find(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(Licences::row).optional();
    }

    Optional<Certificate> forSong(UUID songId, String scope) {
        return jdbc.sql(SELECT + " WHERE song_id = :song AND scope = :scope")
                .param("song", songId).param("scope", scope).query(Licences::row).optional();
    }

    Optional<Certificate> latestForSong(UUID songId) {
        return jdbc.sql(SELECT + " WHERE song_id = :song AND revoked_at IS NULL ORDER BY issued_at DESC LIMIT 1")
                .param("song", songId).query(Licences::row).optional();
    }

    /** Lyrics count as human if the creator wrote or edited them (ledger events). */
    private boolean humanLyrics(UUID songId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM provenance_event
                                WHERE song_id = :song AND type IN ('lyrics_written', 'lyrics_edited'))
                """)
                .param("song", songId).query(Boolean.class).single();
    }

    private static String masterSha(SongStore.Take take) {
        for (String name : List.of("wav", "mp3_320", "mp3_192", "preview")) {
            SongStore.Asset a = take.assets().get(name);
            if (a != null && a.sha256() != null) {
                return a.sha256();
            }
        }
        return null;
    }

    /** {@code BM-XXXX-XXXX-XXXX} in Crockford base32: 60 random bits, easy to read aloud. */
    static String newId() {
        StringBuilder out = new StringBuilder("BM");
        for (int g = 0; g < 3; g++) {
            out.append('-');
            for (int i = 0; i < 4; i++) {
                out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return out.toString();
    }

    private static final String SELECT = """
            SELECT id, song_id, licensee_user_id, scope, title, title_latin, language_tag, plan_at_creation,
                   song_created_at, human_lyrics, master_sha256, issued_at, revoked_at
              FROM certificate
            """;

    private static Certificate row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Certificate(rs.getString("id"), rs.getObject("song_id", UUID.class),
                rs.getObject("licensee_user_id", UUID.class), rs.getString("scope"), rs.getString("title"),
                rs.getString("title_latin"), rs.getString("language_tag"), rs.getString("plan_at_creation"),
                rs.getObject("song_created_at", OffsetDateTime.class), rs.getBoolean("human_lyrics"),
                rs.getString("master_sha256"), rs.getObject("issued_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class));
    }
}
