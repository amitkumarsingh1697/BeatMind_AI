package ai.beatmind.generation;

import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deleted songs leave the library at once; 30 days later their files are removed from storage and
 * their prompt and lyrics are cleared (TDD 5.2, privacy policy section 5). Certificates and the
 * provenance ledger stay: they hold hashes, not the text or audio, and are evidence (DPIA 6).
 */
@Component
class SongPurge {

    private static final Logger log = LoggerFactory.getLogger(SongPurge.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AudioStore audio;
    private final ProvenanceLedger ledger;
    private final TransactionTemplate tx;
    private final Duration after;

    SongPurge(JdbcClient jdbc, JsonMapper json, AudioStore audio, ProvenanceLedger ledger, TransactionTemplate tx,
              @Value("${beatmind.retention.deleted-songs:30d}") Duration after) {
        this.jdbc = jdbc;
        this.json = json;
        this.audio = audio;
        this.ledger = ledger;
        this.tx = tx;
        this.after = after;
    }

    @Scheduled(fixedDelayString = "${beatmind.retention.purge-every:1h}", initialDelayString = "1m")
    void tick() {
        try {
            int n = runOnce();
            if (n > 0) {
                log.info("purged files of {} deleted songs", n);
            }
        } catch (RuntimeException e) {
            log.warn("purging deleted songs failed: {}", e.getMessage());
        }
    }

    /** Purges every song deleted longer ago than the retention period. Returns how many. */
    int runOnce() {
        int done = 0;
        while (true) {
            Boolean purged = tx.execute(status -> purgeNext());
            if (!Boolean.TRUE.equals(purged)) {
                return done;
            }
            done++;
        }
    }

    private boolean purgeNext() {
        List<UUID> ids = jdbc.sql("""
                SELECT id FROM song
                 WHERE deleted_at IS NOT NULL AND purged_at IS NULL
                   AND deleted_at < now() - make_interval(secs => :after)
                 ORDER BY deleted_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """)
                .param("after", after.toSeconds()).query(UUID.class).list();
        if (ids.isEmpty()) {
            return false;
        }
        UUID id = ids.getFirst();
        Set<String> keys = new LinkedHashSet<>();
        jdbc.sql("SELECT assets::text FROM take WHERE song_id = :id").param("id", id).query(String.class).list()
                .forEach(a -> json.readTree(a).properties().forEach(e -> addKey(keys, e.getValue())));
        // The worker's raw takes, kept on the job result.
        jdbc.sql("SELECT j.result::text FROM generation_job j JOIN song s ON s.job_id = j.id WHERE s.id = :id AND j.result IS NOT NULL")
                .param("id", id).query(String.class).optional()
                .ifPresent(r -> json.readTree(r).path("takes").forEach(t -> {
                    addKey(keys, t);
                    t.path("assets").properties().forEach(e -> addKey(keys, e.getValue()));
                }));
        // Edited versions (US-038) with their unwatermarked renders, and separated stems (US-039).
        jdbc.sql("SELECT assets::text AS assets, raw_key FROM take_version WHERE song_id = :id").param("id", id)
                .query((rs, n) -> {
                    json.readTree(rs.getString("assets")).properties().forEach(e -> addKey(keys, e.getValue()));
                    if (rs.getString("raw_key") != null) {
                        keys.add(rs.getString("raw_key"));
                    }
                    return null;
                }).list();
        jdbc.sql("SELECT assets::text FROM take_stems WHERE song_id = :id").param("id", id).query(String.class).list()
                .forEach(a -> json.readTree(a).properties().forEach(e -> addKey(keys, e.getValue())));
        for (String key : keys) {
            audio.delete(key);
        }
        jdbc.sql("UPDATE take SET assets = '{}'::jsonb WHERE song_id = :id").param("id", id).update();
        jdbc.sql("UPDATE take_version SET assets = '{}'::jsonb, raw_key = NULL WHERE song_id = :id").param("id", id).update();
        jdbc.sql("UPDATE take_stems SET assets = '{}'::jsonb WHERE song_id = :id").param("id", id).update();
        jdbc.sql("""
                UPDATE song SET title = 'Deleted song', title_latin = 'Deleted song', prompt = '', lyrics = NULL,
                                search_text = '', search_key = '', client_ip_hash = NULL,
                                purged_at = now()
                 WHERE id = :id
                """).param("id", id).update();
        jdbc.sql("UPDATE generation_job SET payload = '{}'::jsonb, result = NULL WHERE id = (SELECT job_id FROM song WHERE id = :id)")
                .param("id", id).update();
        ledger.append(id, "files_purged", Actor.SYSTEM, Map.of("objects", keys.size()));
        return true;
    }

    private static void addKey(Set<String> keys, JsonNode node) {
        String key = node.path("key").asString("");
        if (!key.isBlank()) {
            keys.add(key);
        }
    }
}
