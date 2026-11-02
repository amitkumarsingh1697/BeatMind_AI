package ai.beatmind.provenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hash-chained provenance ledger, one chain per song (TDD 6.1).
 *
 * <p>Each event's {@code body} is canonical JSON (keys sorted, no spaces) holding the song id,
 * sequence number, type, actor, payload, time and the previous hash. Its {@code hash} is
 * SHA-256 of the previous hash followed by the body. Changing any stored event breaks
 * {@link #verify(UUID)}. The table also rejects UPDATE and DELETE (migration V4).
 *
 * <p>Payloads hold hashes and settings, not the user's text, so the ledger can be kept as evidence
 * after a song is deleted without keeping the lyrics.
 */
@Component
public class ProvenanceLedger {

    /** Who did it: the creator, the model, or BeatMind itself. */
    public enum Actor { HUMAN, AI, SYSTEM }

    /** One ledger event as stored. */
    public record Event(long seq, String type, String actor, Map<String, Object> payload, Instant createdAt,
                        String prevHash, String hash) {}

    /** Result of checking one song's chain. {@code brokenAtSeq} is 0 when the chain verifies. */
    public record Verification(UUID songId, int events, boolean valid, int brokenAtSeq, String reason) {}

    /** Result of checking every chain. */
    public record Summary(int songs, int events, boolean valid, List<Verification> broken) {}

    static final String GENESIS = "0".repeat(64);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    ProvenanceLedger(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = Clock.systemUTC();
    }

    /**
     * Appends an event to the song's chain. Runs in the caller's transaction if there is one, so
     * the event commits with the change it records. Appends to one song are serialised with a
     * transaction-scoped advisory lock.
     */
    @Transactional
    public Event append(UUID songId, String type, Actor actor, Map<String, ?> payload) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked")
                .param("key", "provenance:" + songId)
                .query(Integer.class).single();
        var last = jdbc.sql("SELECT seq, hash FROM provenance_event WHERE song_id = :song ORDER BY seq DESC LIMIT 1")
                .param("song", songId)
                .query((rs, n) -> Map.entry(rs.getInt("seq"), rs.getString("hash")))
                .optional();
        int seq = last.map(Map.Entry::getKey).orElse(0) + 1;
        String prevHash = last.map(Map.Entry::getValue).orElse(GENESIS);
        // Microseconds, the precision PostgreSQL keeps, so the stored time matches the body.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        String actorName = actor.name().toLowerCase(java.util.Locale.ROOT);
        String body = body(songId, seq, type, actorName, payload, now, prevHash);
        String hash = sha256(prevHash + body);
        jdbc.sql("""
                INSERT INTO provenance_event (song_id, seq, type, actor, body, prev_hash, hash, created_at)
                VALUES (:song, :seq, :type, :actor, :body, :prev, :hash, :at)
                """)
                .param("song", songId)
                .param("seq", seq)
                .param("type", type)
                .param("actor", actorName)
                .param("body", body)
                .param("prev", prevHash)
                .param("hash", hash)
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return new Event(seq, type, actorName, new TreeMap<>(payload), now, prevHash, hash);
    }

    /** The song's events in order. */
    public List<Event> events(UUID songId) {
        return jdbc.sql("SELECT seq, type, actor, body, prev_hash, hash, created_at FROM provenance_event WHERE song_id = :song ORDER BY seq")
                .param("song", songId)
                .query((rs, n) -> {
                    Map<String, Object> body = parse(rs.getString("body"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) body.getOrDefault("payload", Map.of());
                    return new Event(rs.getInt("seq"), rs.getString("type"), rs.getString("actor"), payload,
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getString("prev_hash"), rs.getString("hash"));
                })
                .list();
    }

    /** The latest hash of the song's chain, or the genesis hash if it has no events. */
    public String head(UUID songId) {
        return jdbc.sql("SELECT hash FROM provenance_event WHERE song_id = :song ORDER BY seq DESC LIMIT 1")
                .param("song", songId)
                .query(String.class)
                .optional()
                .orElse(GENESIS);
    }

    /** Recomputes the song's chain from the stored rows. */
    public Verification verify(UUID songId) {
        record Row(int seq, String type, String actor, String body, String prev, String hash, OffsetDateTime at) {}
        List<Row> rows = jdbc.sql("SELECT seq, type, actor, body, prev_hash, hash, created_at FROM provenance_event WHERE song_id = :song ORDER BY seq")
                .param("song", songId)
                .query((rs, n) -> new Row(rs.getInt("seq"), rs.getString("type"), rs.getString("actor"),
                        rs.getString("body"), rs.getString("prev_hash"), rs.getString("hash"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        String expectedPrev = GENESIS;
        int expectedSeq = 1;
        for (Row row : rows) {
            String reason = null;
            if (row.seq() != expectedSeq) {
                reason = "sequence gap";
            } else if (!row.prev().equals(expectedPrev)) {
                reason = "previous hash does not match";
            } else if (!sha256(row.prev() + row.body()).equals(row.hash())) {
                reason = "hash does not match the event";
            } else {
                Map<String, Object> body = parse(row.body());
                boolean consistent = songId.toString().equals(body.get("songId"))
                        && Integer.valueOf(row.seq()).equals(asInt(body.get("seq")))
                        && row.type().equals(body.get("type"))
                        && row.actor().equals(body.get("actor"))
                        && row.prev().equals(body.get("prevHash"))
                        && row.at().toInstant().toString().equals(body.get("createdAt"));
                if (!consistent) {
                    reason = "stored columns differ from the hashed event";
                }
            }
            if (reason != null) {
                return new Verification(songId, rows.size(), false, row.seq(), reason);
            }
            expectedPrev = row.hash();
            expectedSeq++;
        }
        return new Verification(songId, rows.size(), true, 0, null);
    }

    /** Verifies every song's chain. */
    public Summary verifyAll() {
        List<UUID> songs = jdbc.sql("SELECT DISTINCT song_id FROM provenance_event").query(UUID.class).list();
        List<Verification> broken = new ArrayList<>();
        int events = 0;
        for (UUID song : songs) {
            Verification v = verify(song);
            events += v.events();
            if (!v.valid()) {
                broken.add(v);
            }
        }
        return new Summary(songs.size(), events, broken.isEmpty(), broken);
    }

    String body(UUID songId, int seq, String type, String actor, Map<String, ?> payload, Instant at, String prevHash) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("songId", songId.toString());
        event.put("seq", seq);
        event.put("type", type);
        event.put("actor", actor);
        event.put("payload", payload);
        event.put("createdAt", at.toString());
        event.put("prevHash", prevHash);
        return json.writeValueAsString(canonical(event));
    }

    /** Sorts map keys at every level so the same event always gives the same bytes. */
    static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ProvenanceLedger::canonical).toList();
        }
        if (value instanceof UUID || value instanceof Instant) {
            return value.toString();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String body) {
        return json.readValue(body, Map.class);
    }

    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
