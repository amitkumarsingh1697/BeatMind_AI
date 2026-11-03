package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * The song editor (US-038, REQ-F-10, REQ-F-11, API-005). Each edit of a take is a new version made
 * by a worker; the take plays and downloads its current version, and any earlier version can be
 * played and restored. Trim and fade are free; extend and regenerate use the model and cost one
 * credit, refunded if the edit fails. One edit per take at a time.
 */
@Service
class Edits {

    static final List<String> OPS = List.of("trim", "fade", "extend", "regenerate");
    static final int MIN_TRIMMED_SECONDS = 5;
    static final int MAX_FADE_SECONDS = 10;
    static final int MIN_EXTEND_SECONDS = 5;
    static final int MAX_EXTEND_SECONDS = 60;
    static final int MIN_SECTION_SECONDS = 2;
    static final int MAX_SECTION_SECONDS = 30;
    static final int MAX_EDITED_SECONDS = 360;
    static final int AI_EDIT_CREDITS = 1;

    record Version(int version, Integer parent, String op, Map<String, Object> params, String status, Double durationSeconds,
                   boolean current, String audioUrl, Map<String, Object> seam, OffsetDateTime createdAt) {}

    private record Row(UUID id, int version, Integer parent, String op, String params, String status, String assets,
                       String rawKey, Double duration, String seam, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SongStore songs;
    private final Credits credits;
    private final JobQueue queue;
    private final ProvenanceLedger ledger;

    Edits(JdbcClient jdbc, JsonMapper json, SongStore songs, Credits credits, JobQueue queue, ProvenanceLedger ledger) {
        this.jdbc = jdbc;
        this.json = json;
        this.songs = songs;
        this.credits = credits;
        this.queue = queue;
        this.ledger = ledger;
    }

    @Transactional
    Version request(CurrentUser me, UUID songId, int takeIndex, String op, Map<String, Object> params, String editPrompt) {
        credits.lock(me.id());
        SongStore.Song song = songs.find(songId, me.id())
                .orElseThrow(() -> new SongRequests.Refusal(HttpStatus.NOT_FOUND, "not_found", "Song not found."));
        if ("disabled".equals(song.rightsStatus())) {
            throw new SongRequests.Refusal(HttpStatus.FORBIDDEN, "disabled", "This song is unavailable.");
        }
        SongStore.Take take = songs.take(songId, takeIndex).filter(t -> "ready".equals(t.status()))
                .orElseThrow(() -> new SongRequests.Refusal(HttpStatus.NOT_FOUND, "not_found", "Take not found."));
        if (!OPS.contains(op)) {
            throw new SongRequests.Refusal(HttpStatus.BAD_REQUEST, "invalid_edit", "Choose trim, fade, extend or regenerate.");
        }
        ensureOriginal(song, take);
        boolean busy = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM take_version WHERE song_id = :song AND take_idx = :idx AND status = 'processing')
                """).param("song", songId).param("idx", takeIndex).query(Boolean.class).single();
        if (busy) {
            throw new SongRequests.Refusal(HttpStatus.CONFLICT, "edit_in_progress",
                    "This take is already being edited. Wait for that edit to finish.");
        }
        Row current = rows(songId, takeIndex).stream().filter(r -> r.version() == take.version()).findFirst()
                .orElseThrow();
        if (current.rawKey() == null) {
            throw new SongRequests.Refusal(HttpStatus.CONFLICT, "not_editable",
                    "This take was made before editing existed. Make a new song to edit it.");
        }
        double duration = current.duration() != null ? current.duration() : song.durationSeconds();
        Map<String, Object> clean = validate(op, params, duration);
        boolean ai = "extend".equals(op) || "regenerate".equals(op);
        if (ai) {
            Credits.Allowance allowance = credits.allowance(me.id(), me.plan());
            if (allowance.remaining() < AI_EDIT_CREDITS) {
                throw new SongRequests.Refusal(HttpStatus.TOO_MANY_REQUESTS, "allowance",
                        "Extending or regenerating uses a credit, and you have none left for now.");
            }
        }
        int next = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM take_version WHERE song_id = :song AND take_idx = :idx")
                .param("song", songId).param("idx", takeIndex).query(Integer.class).single();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("songId", songId.toString());
        payload.put("takeIndex", takeIndex);
        payload.put("version", next);
        payload.put("op", op);
        payload.put("params", clean);
        payload.put("sourceKey", current.rawKey());
        payload.put("title", song.title());
        payload.put("languageTag", song.languageTag());
        payload.put("rights", song.rightsStatus());
        payload.put("prompt", song.prompt());
        if (editPrompt != null && !editPrompt.isBlank()) {
            payload.put("editPrompt", editPrompt.strip());
        }
        payload.put("instrumental", song.instrumental());
        payload.put("durationSeconds", song.durationSeconds());
        if (song.beatJson() != null) {
            payload.put("beat", SongViews.beat(song.beatJson()));
        }
        UUID jobId = queue.enqueue(JobQueue.Kind.EDIT, json.writeValueAsString(payload), "pro".equals(me.plan()) ? 1 : 0, me.id());
        jdbc.sql("""
                INSERT INTO take_version (id, song_id, take_idx, version, parent, op, params, status, job_id)
                VALUES (:id, :song, :idx, :version, :parent, :op, CAST(:params AS jsonb), 'processing', :job)
                """).param("id", UUID.randomUUID()).param("song", songId).param("idx", takeIndex).param("version", next)
                .param("parent", current.version()).param("op", op).param("params", json.writeValueAsString(clean))
                .param("job", jobId).update();
        if (ai) {
            credits.debit(me.id(), jobId, songId, AI_EDIT_CREDITS);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("takeIndex", takeIndex);
        event.put("version", next);
        event.put("parent", current.version());
        event.put("op", op);
        event.put("params", clean);
        if (editPrompt != null && !editPrompt.isBlank()) {
            event.put("editPromptSha256", SongRequests.sha256(editPrompt.strip()));
        }
        ledger.append(songId, "edit_requested", Actor.HUMAN, event);
        return versions(songId, takeIndex).stream().filter(v -> v.version() == next).findFirst().orElseThrow();
    }

    /** Checks the numbers for an edit against the current version's length. */
    static Map<String, Object> validate(String op, Map<String, Object> params, double duration) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (op) {
            case "trim" -> {
                double start = number(params, "start", 0);
                double end = number(params, "end", duration);
                if (start < 0 || end > duration + 0.01 || end - start < MIN_TRIMMED_SECONDS) {
                    throw invalid("Keep at least " + MIN_TRIMMED_SECONDS + " seconds, inside the take.");
                }
                out.put("start", round(start));
                out.put("end", round(Math.min(end, duration)));
            }
            case "fade" -> {
                double in = number(params, "fadeIn", 0);
                double outS = number(params, "fadeOut", 0);
                if (in < 0 || outS < 0 || in > MAX_FADE_SECONDS || outS > MAX_FADE_SECONDS || in + outS <= 0
                        || in + outS > duration) {
                    throw invalid("Fades can be up to " + MAX_FADE_SECONDS + " seconds each.");
                }
                out.put("fadeIn", round(in));
                out.put("fadeOut", round(outS));
            }
            case "extend" -> {
                double seconds = number(params, "seconds", 0);
                if (seconds < MIN_EXTEND_SECONDS || seconds > MAX_EXTEND_SECONDS || duration + seconds > MAX_EDITED_SECONDS) {
                    throw invalid("Extend by " + MIN_EXTEND_SECONDS + " to " + MAX_EXTEND_SECONDS
                            + " seconds, up to " + MAX_EDITED_SECONDS / 60 + " minutes in all.");
                }
                out.put("seconds", round(seconds));
            }
            case "regenerate" -> {
                double start = number(params, "start", -1);
                double end = number(params, "end", -1);
                double length = end - start;
                if (start < 0 || end > duration + 0.01 || length < MIN_SECTION_SECONDS || length > MAX_SECTION_SECONDS) {
                    throw invalid("Choose a section of " + MIN_SECTION_SECONDS + " to " + MAX_SECTION_SECONDS
                            + " seconds inside the take.");
                }
                out.put("start", round(start));
                out.put("end", round(Math.min(end, duration)));
            }
            default -> throw invalid("Choose trim, fade, extend or regenerate.");
        }
        return out;
    }

    /** Called by the job finalizer for edit jobs. */
    void finish(UUID jobId, UUID userId, String status, String result) {
        record V(UUID songId, int idx, int version, Integer parent, String op, String params) {}
        Optional<V> found = jdbc.sql("""
                SELECT song_id, take_idx, version, parent, op, params::text FROM take_version
                 WHERE job_id = :job AND status = 'processing' FOR UPDATE
                """).param("job", jobId)
                .query((rs, n) -> new V(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3),
                        (Integer) rs.getObject(4), rs.getString(5), rs.getString(6)))
                .optional();
        if (found.isEmpty()) {
            return;
        }
        V v = found.get();
        boolean ai = "extend".equals(v.op()) || "regenerate".equals(v.op());
        JsonNode entry = "succeeded".equals(status) && result != null ? json.readTree(result).path("takes").path(0) : null;
        if (entry == null || !entry.path("assets").isObject()) {
            jdbc.sql("UPDATE take_version SET status = 'failed' WHERE job_id = :job").param("job", jobId).update();
            int refunded = ai ? credits.refund(userId, jobId, v.songId(), AI_EDIT_CREDITS, "refund_failed") : 0;
            ledger.append(v.songId(), "edit_failed", Actor.SYSTEM, Map.of(
                    "takeIndex", v.idx(), "version", v.version(), "op", v.op(), "creditsRefunded", refunded));
            return;
        }
        String assets = json.writeValueAsString(entry.get("assets"));
        JsonNode seam = entry.path("seam");
        jdbc.sql("""
                UPDATE take_version SET status = 'ready', assets = CAST(:assets AS jsonb), raw_key = :raw,
                       watermark_id = :wm, duration_s = :duration, seam = CAST(:seam AS jsonb)
                 WHERE job_id = :job
                """).param("assets", assets).param("raw", entry.path("rawKey").asString(null))
                .param("wm", entry.hasNonNull("watermarkId") ? entry.get("watermarkId").asLong() : null)
                .param("duration", entry.hasNonNull("durationSeconds") ? BigDecimal.valueOf(entry.get("durationSeconds").asDouble()) : null)
                .param("seam", seam.isObject() ? json.writeValueAsString(seam) : null)
                .param("job", jobId).update();
        jdbc.sql("UPDATE take SET assets = CAST(:assets AS jsonb), current_version = :v WHERE song_id = :song AND idx = :idx")
                .param("assets", assets).param("v", v.version()).param("song", v.songId()).param("idx", v.idx()).update();
        Map<String, Object> hashes = new LinkedHashMap<>();
        for (var e : entry.get("assets").properties()) {
            if (e.getValue().hasNonNull("sha256")) {
                hashes.put(e.getKey(), e.getValue().get("sha256").asString());
            }
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("takeIndex", v.idx());
        event.put("version", v.version());
        event.put("parent", v.parent());
        event.put("op", v.op());
        event.put("params", json.readValue(v.params(), new TypeReference<Map<String, Object>>() {}));
        event.put("runner", entry.path("runner").asString("editor"));
        event.put("fileSha256", hashes);
        if (seam.isObject()) {
            event.put("seamPassed", seam.path("passed").asBoolean(false));
            event.put("seamWorstRatio", seam.path("worstRatio").asDouble());
        }
        // Trim and fade are the creator's own edits; extend and regenerate are new AI audio.
        ledger.append(v.songId(), "take_edited", ai ? Actor.AI : Actor.HUMAN, event);
    }

    @Transactional
    Version restore(CurrentUser me, UUID songId, int takeIndex, int version) {
        songs.find(songId, me.id())
                .orElseThrow(() -> new SongRequests.Refusal(HttpStatus.NOT_FOUND, "not_found", "Song not found."));
        Row row = rows(songId, takeIndex).stream().filter(r -> r.version() == version && "ready".equals(r.status()))
                .findFirst()
                .orElseThrow(() -> new SongRequests.Refusal(HttpStatus.NOT_FOUND, "not_found", "Version not found."));
        jdbc.sql("UPDATE take SET assets = CAST(:assets AS jsonb), current_version = :v WHERE song_id = :song AND idx = :idx")
                .param("assets", row.assets()).param("v", version).param("song", songId).param("idx", takeIndex).update();
        ledger.append(songId, "version_restored", Actor.HUMAN, Map.of("takeIndex", takeIndex, "version", version));
        return versions(songId, takeIndex).stream().filter(v -> v.version() == version).findFirst().orElseThrow();
    }

    List<Version> versions(UUID songId, int takeIndex) {
        int current = jdbc.sql("SELECT current_version FROM take WHERE song_id = :song AND idx = :idx")
                .param("song", songId).param("idx", takeIndex).query(Integer.class).optional().orElse(1);
        return rows(songId, takeIndex).stream().map(r -> new Version(r.version(), r.parent(), r.op(),
                json.readValue(r.params(), new TypeReference<Map<String, Object>>() {}), r.status(), r.duration(),
                r.version() == current,
                "ready".equals(r.status()) ? "/v1/songs/" + songId + "/takes/" + takeIndex + "/versions/" + r.version() + "/audio" : null,
                r.seam() == null ? null : json.readValue(r.seam(), new TypeReference<Map<String, Object>>() {}),
                r.createdAt())).toList();
    }

    /** The preview file of a ready version, for comparing versions in the player. */
    Optional<SongStore.Asset> previewOf(UUID songId, int takeIndex, int version) {
        return rows(songId, takeIndex).stream().filter(r -> r.version() == version && "ready".equals(r.status()))
                .findFirst().map(r -> {
                    JsonNode a = json.readTree(r.assets());
                    for (String name : List.of("mp3_192", "preview", "mp3_320", "wav")) {
                        JsonNode x = a.path(name);
                        if (x.isObject() && !x.path("key").asString("").isEmpty()) {
                            return new SongStore.Asset(x.path("key").asString(), x.path("contentType").asString("audio/mpeg"),
                                    x.path("sha256").asString(null), x.path("bytes").asLong(0));
                        }
                    }
                    return null;
                });
    }

    /** The unwatermarked render of a take's current version, which stems are separated from (US-039). */
    record Source(int version, String rawKey) {}

    Optional<Source> currentSource(SongStore.Song song, SongStore.Take take) {
        ensureOriginal(song, take);
        return rows(song.id(), take.index()).stream()
                .filter(r -> r.version() == take.version() && "ready".equals(r.status()) && r.rawKey() != null)
                .findFirst().map(r -> new Source(r.version(), r.rawKey()));
    }

    /** Version 1 is the take as generated; it is recorded the first time the take is edited. */
    void ensureOriginal(SongStore.Song song, SongStore.Take take) {
        String raw = null;
        double duration = song.durationSeconds();
        if (song.jobId() != null) {
            String result = jdbc.sql("SELECT result::text FROM generation_job WHERE id = :id").param("id", song.jobId())
                    .query(String.class).optional().orElse(null);
            if (result != null) {
                JsonNode t = json.readTree(result).path("takes").path(take.index() - 1);
                raw = t.path("key").asString(null);
                // The length the finisher measured, not the length that was asked for.
                if (t.path("durationSeconds").isNumber()) {
                    duration = t.path("durationSeconds").asDouble();
                }
            }
        }
        jdbc.sql("""
                INSERT INTO take_version (id, song_id, take_idx, version, op, status, assets, raw_key, watermark_id, duration_s)
                SELECT :id, :song, :idx, 1, 'original', 'ready', t.assets, :raw, t.watermark_id, :duration
                  FROM take t WHERE t.song_id = :song AND t.idx = :idx
                ON CONFLICT (song_id, take_idx, version) DO NOTHING
                """).param("id", UUID.randomUUID()).param("song", song.id()).param("idx", take.index()).param("raw", raw)
                .param("duration", BigDecimal.valueOf(duration)).update();
    }

    private List<Row> rows(UUID songId, int takeIndex) {
        return jdbc.sql("""
                SELECT id, version, parent, op, params::text AS params, status, assets::text AS assets, raw_key,
                       duration_s, seam::text AS seam, created_at
                  FROM take_version WHERE song_id = :song AND take_idx = :idx ORDER BY version
                """).param("song", songId).param("idx", takeIndex)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getInt("version"), (Integer) rs.getObject("parent"),
                        rs.getString("op"), rs.getString("params"), rs.getString("status"), rs.getString("assets"),
                        rs.getString("raw_key"), rs.getObject("duration_s") == null ? null : rs.getDouble("duration_s"),
                        rs.getString("seam"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private static double number(Map<String, Object> params, String key, double fallback) {
        Object v = params == null ? null : params.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw invalid("Use numbers of seconds.");
        }
    }

    private static double round(double seconds) {
        return Math.round(seconds * 100) / 100.0;
    }

    private static SongRequests.Refusal invalid(String message) {
        return new SongRequests.Refusal(HttpStatus.BAD_REQUEST, "invalid_edit", message);
    }
}
