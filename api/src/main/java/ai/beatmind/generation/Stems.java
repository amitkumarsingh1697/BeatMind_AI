package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Separated stems (US-039, REQ-F-23): vocals, drums, bass and other, for one version of a take.
 * Paid plans, and songs the person owns (REQ-F-58), can ask for them; they cost no credits. A worker
 * separates the unwatermarked render of the current version, checks that the four stems add back
 * up to it, and finishes each stem like a take (watermark, AI label, C2PA). Each version is
 * separated once; an edit makes a new version, which can be separated again.
 */
@Service
class Stems {

    static final List<String> NAMES = List.of("vocals", "drums", "bass", "other");

    record Stem(String name, long bytes, String sha256) {}

    record View(String status, int version, boolean current, List<Stem> stems, Map<String, Object> sumCheck,
                String model, OffsetDateTime createdAt) {}

    private record Row(int version, String status, String assets, String sumCheck, String model, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SongStore songs;
    private final Edits edits;
    private final JobQueue queue;
    private final ProvenanceLedger ledger;

    Stems(JdbcClient jdbc, JsonMapper json, SongStore songs, Edits edits, JobQueue queue, ProvenanceLedger ledger) {
        this.jdbc = jdbc;
        this.json = json;
        this.songs = songs;
        this.edits = edits;
        this.queue = queue;
        this.ledger = ledger;
    }

    static boolean allowed(String viewerPlan, String rights) {
        return !"disabled".equals(rights) && (Entitlements.paid(viewerPlan) || "user_owned".equals(rights));
    }

    @Transactional
    View request(CurrentUser me, UUID songId, int takeIndex) {
        SongStore.Song song = songs.find(songId, me.id())
                .orElseThrow(() -> new SongRequests.Refusal(HttpStatus.NOT_FOUND, "not_found", "Song not found."));
        if ("disabled".equals(song.rightsStatus())) {
            throw new SongRequests.Refusal(HttpStatus.FORBIDDEN, "disabled", "This song is unavailable.");
        }
        if (!allowed(me.plan(), song.rightsStatus())) {
            throw new SongRequests.Refusal(HttpStatus.FORBIDDEN, "plan", "Stems come with the Creator and Pro plans.");
        }
        SongStore.Take take = songs.take(songId, takeIndex).filter(t -> "ready".equals(t.status()))
                .orElseThrow(() -> new SongRequests.Refusal(HttpStatus.NOT_FOUND, "not_found", "Take not found."));
        jdbc.sql("SELECT id FROM take WHERE id = :id FOR UPDATE").param("id", take.id()).query(UUID.class).single();
        Edits.Source source = edits.currentSource(song, take).orElseThrow(() -> new SongRequests.Refusal(
                HttpStatus.CONFLICT, "not_editable", "This take was made before stems existed. Make a new song for stems."));
        Optional<Row> existing = row(songId, takeIndex, source.version());
        if (existing.isPresent() && !"failed".equals(existing.get().status())) {
            return view(existing.get(), source.version());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("songId", songId.toString());
        payload.put("takeIndex", takeIndex);
        payload.put("version", source.version());
        payload.put("sourceKey", source.rawKey());
        payload.put("title", song.title());
        payload.put("languageTag", song.languageTag());
        payload.put("rights", song.rightsStatus());
        payload.put("instrumental", song.instrumental());
        UUID job = queue.enqueue(JobQueue.Kind.STEMS, json.writeValueAsString(payload), "pro".equals(me.plan()) ? 1 : 0, me.id());
        jdbc.sql("""
                INSERT INTO take_stems (id, song_id, take_idx, version, status, job_id)
                VALUES (:id, :song, :idx, :version, 'processing', :job)
                ON CONFLICT (song_id, take_idx, version)
                DO UPDATE SET status = 'processing', job_id = EXCLUDED.job_id, assets = '{}'::jsonb, sum_check = NULL,
                              created_at = now()
                """).param("id", UUID.randomUUID()).param("song", songId).param("idx", takeIndex)
                .param("version", source.version()).param("job", job).update();
        ledger.append(songId, "stems_requested", Actor.HUMAN, Map.of("takeIndex", takeIndex, "version", source.version()));
        return view(row(songId, takeIndex, source.version()).orElseThrow(), source.version());
    }

    /** The stems of the take's current version, if any were asked for. */
    Optional<View> current(UUID songId, int takeIndex) {
        int version = songs.take(songId, takeIndex).map(SongStore.Take::version).orElse(1);
        return row(songId, takeIndex, version).map(r -> view(r, version));
    }

    /** A finished stem file of the current version, for a signed download. */
    Optional<SongStore.Asset> asset(UUID songId, int takeIndex, int version, String name) {
        return row(songId, takeIndex, version).filter(r -> "ready".equals(r.status()))
                .map(r -> json.readTree(r.assets()).path(name))
                .filter(JsonNode::isObject)
                .map(a -> new SongStore.Asset(a.path("key").asString(), a.path("contentType").asString("audio/wav"),
                        a.path("sha256").asString(null), a.path("bytes").asLong(0)));
    }

    /** Called by the job finalizer for stems jobs. */
    void finish(UUID jobId, String status, String result) {
        record R(UUID songId, int idx, int version) {}
        Optional<R> found = jdbc.sql("""
                SELECT song_id, take_idx, version FROM take_stems WHERE job_id = :job AND status = 'processing' FOR UPDATE
                """).param("job", jobId)
                .query((rs, n) -> new R(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3))).optional();
        if (found.isEmpty()) {
            return;
        }
        R r = found.get();
        JsonNode out = "succeeded".equals(status) && result != null ? json.readTree(result) : null;
        JsonNode assets = out == null ? null : out.path("stems");
        boolean complete = assets != null && NAMES.stream().allMatch(n -> assets.path(n).path("key").isString());
        if (!complete) {
            jdbc.sql("UPDATE take_stems SET status = 'failed' WHERE job_id = :job").param("job", jobId).update();
            ledger.append(r.songId(), "stems_failed", Actor.SYSTEM, Map.of("takeIndex", r.idx(), "version", r.version()));
            return;
        }
        JsonNode sum = out.path("sumCheck");
        jdbc.sql("""
                UPDATE take_stems SET status = 'ready', assets = CAST(:assets AS jsonb), sum_check = CAST(:sum AS jsonb),
                       model = :model
                 WHERE job_id = :job
                """).param("assets", json.writeValueAsString(assets))
                .param("sum", sum.isObject() ? json.writeValueAsString(sum) : null)
                .param("model", out.path("model").asString(null)).param("job", jobId).update();
        Map<String, Object> hashes = new LinkedHashMap<>();
        for (String n : NAMES) {
            hashes.put(n, assets.path(n).path("sha256").asString(""));
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("takeIndex", r.idx());
        event.put("version", r.version());
        event.put("model", out.path("model").asString("unknown"));
        event.put("fileSha256", hashes);
        if (sum.isObject()) {
            event.put("sumPassed", sum.path("passed").asBoolean(false));
        }
        ledger.append(r.songId(), "stems_separated", Actor.AI, event);
    }

    private Optional<Row> row(UUID songId, int takeIndex, int version) {
        return jdbc.sql("""
                SELECT version, status, assets::text AS assets, sum_check::text AS sum_check, model, created_at
                  FROM take_stems WHERE song_id = :song AND take_idx = :idx AND version = :version
                """).param("song", songId).param("idx", takeIndex).param("version", version)
                .query((rs, n) -> new Row(rs.getInt("version"), rs.getString("status"), rs.getString("assets"),
                        rs.getString("sum_check"), rs.getString("model"), rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    private View view(Row r, int currentVersion) {
        List<Stem> list = new ArrayList<>();
        if ("ready".equals(r.status())) {
            JsonNode a = json.readTree(r.assets());
            for (String n : NAMES) {
                list.add(new Stem(n, a.path(n).path("bytes").asLong(0), a.path(n).path("sha256").asString(null)));
            }
        }
        Map<String, Object> sum = r.sumCheck() == null ? null
                : json.readValue(r.sumCheck(), new TypeReference<Map<String, Object>>() {});
        return new View(r.status(), r.version(), r.version() == currentVersion, list, sum, r.model(), r.createdAt());
    }
}
