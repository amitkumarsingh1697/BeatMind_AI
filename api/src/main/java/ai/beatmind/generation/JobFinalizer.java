package ai.beatmind.generation;

import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records what a finished job made (US-019, US-027): a take row per take, a ledger event per take,
 * and an automatic refund for every failed, timed-out or withheld take (REQ-F-56). Runs about
 * once a second; each job is handled once, in its own transaction.
 */
@Component
class JobFinalizer {

    private static final Logger log = LoggerFactory.getLogger(JobFinalizer.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SongStore songs;
    private final Credits credits;
    private final ProvenanceLedger ledger;
    private final TransactionTemplate tx;
    private final LyricDrafts drafts;
    private final ModerationCases cases;
    private final Edits edits;
    private final Verifier verifier;

    JobFinalizer(JdbcClient jdbc, JsonMapper json, SongStore songs, Credits credits, ProvenanceLedger ledger,
                 TransactionTemplate tx, LyricDrafts drafts, ModerationCases cases, Edits edits, Verifier verifier) {
        this.edits = edits;
        this.verifier = verifier;
        this.drafts = drafts;
        this.cases = cases;
        this.jdbc = jdbc;
        this.json = json;
        this.songs = songs;
        this.credits = credits;
        this.ledger = ledger;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${beatmind.generation.finalize-every:1s}", initialDelayString = "2s")
    void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("finalizing jobs failed: {}", e.getMessage());
        }
    }

    /** Finalizes finished jobs. Returns how many it handled. */
    int runOnce() {
        int handled = 0;
        while (true) {
            Integer done = tx.execute(status -> finalizeNext());
            if (done == null || done == 0) {
                return handled;
            }
            handled++;
        }
    }

    private int finalizeNext() {
        record Job(UUID id, UUID userId, String status, String result, String kind, String provider) {}
        List<Job> jobs = jdbc.sql("""
                SELECT id, user_id, status, result::text AS result, kind, provider FROM generation_job
                 WHERE finalized_at IS NULL AND status IN ('succeeded', 'dead')
                 ORDER BY finished_at NULLS FIRST LIMIT 1 FOR UPDATE SKIP LOCKED
                """)
                .query((rs, n) -> new Job(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("status"), rs.getString("result"), rs.getString("kind"), rs.getString("provider")))
                .list();
        if (jobs.isEmpty()) {
            return 0;
        }
        Job job = jobs.getFirst();
        if (!"self".equals(job.provider())) {
            // US-016: the fallback API's actual cost replaces the estimate in the daily spend.
            BigDecimal cost = BigDecimal.ZERO;
            if ("succeeded".equals(job.status()) && job.result() != null) {
                JsonNode c = json.readTree(job.result()).path("costUsd");
                cost = c.isNumber() ? c.decimalValue() : null;
            }
            jdbc.sql("UPDATE generation_job SET cost_actual_usd = coalesce(:cost, cost_estimate_usd) WHERE id = :id")
                    .param("cost", cost).param("id", job.id()).update();
        }
        var song = "edit".equals(job.kind()) || "verify".equals(job.kind()) ? java.util.Optional.<SongStore.Song>empty()
                : songs.byJob(job.id());
        if ("edit".equals(job.kind())) {
            edits.finish(job.id(), job.userId(), job.status(), job.result());
        } else if ("verify".equals(job.kind())) {
            verifier.finish(job.id(), job.status(), job.result());
        } else if (song.isPresent()) {
            record(job.id(), job.userId(), job.status(), job.result(), song.get());
        } else {
            drafts.finish(job.id(), job.userId(), job.status(), job.result());
        }
        jdbc.sql("UPDATE generation_job SET finalized_at = now() WHERE id = :id").param("id", job.id()).update();
        return 1;
    }

    private void record(UUID jobId, UUID userId, String status, String result, SongStore.Song song) {
        int expected = Entitlements.TAKES_PER_REQUEST;
        int withheld = 0;
        int made = 0;
        if ("succeeded".equals(status) && result != null) {
            JsonNode list = json.readTree(result).path("takes");
            for (int i = 0; i < list.size(); i++) {
                JsonNode t = list.get(i);
                int index = i + 1;
                boolean isWithheld = t.path("withheld").asBoolean(false);
                Map<String, Object> assets = assets(t);
                Long watermark = t.hasNonNull("watermarkId") ? t.get("watermarkId").asLong() : null;
                JsonNode screen = t.path("similarity");
                // US-015: the worker's tempo check on beat-mode takes.
                JsonNode tempo = t.path("tempo");
                jdbc.sql("""
                        INSERT INTO take (id, song_id, idx, status, watermark_id, assets, screen, analysis)
                        VALUES (:id, :song, :idx, :status, :wm, CAST(:assets AS jsonb), CAST(:screen AS jsonb),
                                CAST(:analysis AS jsonb))
                        ON CONFLICT (song_id, idx) DO NOTHING
                        """)
                        .param("id", UUID.randomUUID())
                        .param("song", song.id())
                        .param("idx", index)
                        .param("status", isWithheld ? "withheld" : "ready")
                        .param("wm", watermark)
                        .param("assets", json.writeValueAsString(assets))
                        .param("screen", screen.isObject() ? json.writeValueAsString(screen) : null)
                        .param("analysis", tempo.isObject() ? json.writeValueAsString(Map.of("tempo", tempo)) : null)
                        .update();
                if (isWithheld) {
                    withheld++;
                    String reason = t.path("withheldReason").asString("safety screen");
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("takeIndex", index);
                    event.put("reason", reason);
                    if (screen.isObject()) {
                        // REQ-F-52: close matches are withheld and logged, for the admin queue too.
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("score", screen.path("score").asDouble());
                        detail.put("matchedHashes", screen.path("matched").asInt());
                        detail.put("referenceId", screen.path("referenceId").asLong());
                        detail.put("referenceTitle", screen.path("referenceTitle").asString(""));
                        event.put("similarityScore", screen.path("score").asDouble());
                        event.put("referenceId", screen.path("referenceId").asLong());
                        cases.recordSimilarity(userId, song.id(), index, detail);
                    }
                    ledger.append(song.id(), "take_withheld", Actor.SYSTEM, event);
                } else {
                    made++;
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("takeIndex", index);
                    payload.put("runner", t.path("runner").asString("unknown"));
                    payload.put("model", t.path("model").asString(t.path("runner").asString("unknown")));
                    if (watermark != null) {
                        payload.put("watermarkId", watermark);
                    }
                    Map<String, Object> hashes = new LinkedHashMap<>();
                    assets.forEach((name, a) -> {
                        Object sha = ((Map<?, ?>) a).get("sha256");
                        if (sha != null) {
                            hashes.put(name, sha);
                        }
                    });
                    payload.put("fileSha256", hashes);
                    payload.put("c2pa", t.path("c2pa").asBoolean(false));
                    if (tempo.isObject()) {
                        payload.put("tempoTargetBpm", tempo.path("targetBpm").asInt());
                        payload.put("tempoMeasuredBpm", tempo.path("measuredBpm").asDouble());
                        payload.put("tempoWithinTolerance", tempo.path("withinTolerance").asBoolean(false));
                    }
                    if (screen.isObject()) {
                        payload.put("similarityScreened", true);
                        payload.put("similarityScore", screen.path("score").asDouble());
                    }
                    ledger.append(song.id(), "take_generated", Actor.AI, payload);
                }
            }
        } else {
            ledger.append(song.id(), "generation_failed", Actor.SYSTEM, Map.of("jobId", jobId.toString()));
        }
        int failed = Math.max(0, expected - made - withheld);
        int refunded = 0;
        if (withheld > 0) {
            refunded += credits.refund(userId, jobId, song.id(), withheld, "refund_withheld");
        }
        if (failed > 0) {
            refunded += credits.refund(userId, jobId, song.id(), failed, "refund_failed");
        }
        if (refunded > 0) {
            ledger.append(song.id(), "credits_refunded", Actor.SYSTEM, Map.of(
                    "credits", refunded, "failedTakes", failed, "withheldTakes", withheld));
        }
    }

    /** Stored files per format. Older results have only {@code key} and {@code format}. */
    private Map<String, Object> assets(JsonNode take) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode assets = take.path("assets");
        if (assets.isObject()) {
            for (var e : assets.properties()) {
                JsonNode a = e.getValue();
                Map<String, Object> asset = new LinkedHashMap<>();
                asset.put("key", a.path("key").asString(""));
                asset.put("contentType", a.path("contentType").asString("application/octet-stream"));
                if (a.hasNonNull("sha256")) {
                    asset.put("sha256", a.get("sha256").asString());
                }
                if (a.hasNonNull("bytes")) {
                    asset.put("bytes", a.get("bytes").asLong());
                }
                out.put(e.getKey(), asset);
            }
        }
        if (out.isEmpty() && take.hasNonNull("key")) {
            String format = take.path("format").asString("wav");
            out.put("preview", Map.of("key", take.get("key").asString(),
                    "contentType", "mp3".equals(format) ? "audio/mpeg" : "audio/wav"));
        }
        return out;
    }
}
