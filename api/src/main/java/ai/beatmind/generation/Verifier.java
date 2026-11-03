package ai.beatmind.generation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public verify page (US-021, REQ-F-32, REQ-SEC-10, API-011). Anyone can ask whether a file or a
 * certificate ID came from BeatMind. A file is first matched by its SHA-256 against every file we
 * stored; if nothing matches, a worker looks for our watermark and C2PA credentials and the upload
 * is deleted. Answers are coarse on purpose: the day it was made, that the music is AI-generated,
 * who wrote the lyrics, and whether it was removed. Never the title, the account or the prompt.
 */
@Service
class Verifier {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    record Check(UUID id, String status, Map<String, Object> result) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final JobQueue queue;
    private final AudioStore audio;
    private final Licences licences;
    private final int perIpPerHour;

    Verifier(JdbcClient jdbc, JsonMapper json, JobQueue queue, AudioStore audio, Licences licences,
             @Value("${beatmind.verify.per-ip-per-hour:20}") int perIpPerHour) {
        this.jdbc = jdbc;
        this.json = json;
        this.queue = queue;
        this.audio = audio;
        this.licences = licences;
        this.perIpPerHour = perIpPerHour;
    }

    private void limit(String ipHash) {
        int recent = jdbc.sql("""
                SELECT count(*) FROM verify_check WHERE client_ip_hash = :ip AND created_at > now() - interval '1 hour'
                """).param("ip", ipHash).query(Integer.class).single();
        if (recent >= perIpPerHour) {
            throw new SongRequests.Refusal(HttpStatus.TOO_MANY_REQUESTS, "velocity",
                    "That is a lot of checks from your network. Please try again in an hour.");
        }
    }

    @Transactional
    Check certificate(String certificateId, String ipHash) {
        limit(ipHash);
        String id = certificateId == null ? "" : certificateId.strip().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> result;
        Optional<Licences.Certificate> c = id.matches("BM-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}") ? licences.find(id) : Optional.empty();
        if (c.isEmpty()) {
            result = Map.of("verdict", "not_found",
                    "message", "No BeatMind certificate has that ID. Check it for typing mistakes.");
        } else {
            result = describe(c.get().songId(), "certificate");
            result.put("certificate", Map.of("id", c.get().id(),
                    "status", c.get().revokedAt() == null ? "valid" : "revoked",
                    "scope", c.get().scope(),
                    "url", "/certificates/" + c.get().id()));
        }
        return save("certificate", null, null, null, "done", result, ipHash);
    }

    @Transactional
    Check file(byte[] data, String ipHash) {
        limit(ipHash);
        String sha = sha256(data);
        Optional<UUID> song = jdbc.sql("""
                SELECT song_id FROM (
                    SELECT t.song_id FROM take t, jsonb_each(t.assets) a WHERE a.value->>'sha256' = :sha
                    UNION ALL
                    SELECT v.song_id FROM take_version v, jsonb_each(v.assets) a WHERE a.value->>'sha256' = :sha
                    UNION ALL
                    SELECT st.song_id FROM take_stems st, jsonb_each(st.assets) a WHERE a.value->>'sha256' = :sha) m
                LIMIT 1
                """).param("sha", sha).query(UUID.class).optional();
        if (song.isPresent()) {
            return save("file", sha, null, null, "done", describe(song.get(), "exact_file"), ipHash);
        }
        UUID id = UUID.randomUUID();
        String key = "verify/" + id;
        audio.put(key, data, "application/octet-stream");
        UUID job = queue.enqueue(JobQueue.Kind.VERIFY, json.writeValueAsString(Map.of("checkId", id.toString(),
                "uploadKey", key)), 2, null);
        return save(id, "file", sha, key, job, "checking", null, ipHash);
    }

    Optional<Check> find(UUID id) {
        return jdbc.sql("SELECT id, status, result::text AS result FROM verify_check WHERE id = :id").param("id", id)
                .query((rs, n) -> new Check(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("result") == null ? null
                                : json.readValue(rs.getString("result"), new TypeReference<Map<String, Object>>() {})))
                .optional();
    }

    /** Called by the job finalizer when the worker has read the file. */
    void finish(UUID jobId, String status, String result) {
        Optional<UUID> check = jdbc.sql("SELECT id FROM verify_check WHERE job_id = :job AND status = 'checking'")
                .param("job", jobId).query(UUID.class).optional();
        if (check.isEmpty()) {
            return;
        }
        Map<String, Object> answer;
        String outcome = "done";
        if (!"succeeded".equals(status) || result == null) {
            outcome = "failed";
            answer = Map.of("verdict", "unreadable", "message", "We could not read that file. Try an MP3 or WAV.");
        } else {
            JsonNode v = json.readTree(result).path("verify");
            JsonNode mark = v.path("watermark");
            JsonNode c2pa = v.path("c2pa");
            Optional<UUID> song = Optional.empty();
            String how = null;
            if (mark.path("found").asBoolean(false) && mark.hasNonNull("payload")) {
                song = jdbc.sql("SELECT song_id FROM take WHERE watermark_id = :wm LIMIT 1")
                        .param("wm", mark.get("payload").asLong()).query(UUID.class).optional();
                how = "watermark";
            }
            if (song.isEmpty() && c2pa.path("beatmind").asBoolean(false) && "Valid".equals(c2pa.path("state").asString(""))) {
                try {
                    song = jdbc.sql("SELECT id FROM song WHERE id = :id").param("id", UUID.fromString(c2pa.path("songId").asString("")))
                            .query(UUID.class).optional();
                    how = "content_credentials";
                } catch (IllegalArgumentException e) {
                    song = Optional.empty();
                }
            }
            if (song.isPresent()) {
                answer = describe(song.get(), how);
            } else if (!v.path("watermark").path("decodable").asBoolean(true)) {
                outcome = "failed";
                answer = Map.of("verdict", "unreadable", "message", "We could not read that file. Try an MP3 or WAV.");
            } else {
                answer = new LinkedHashMap<>(Map.of("verdict", "not_found",
                        "message", "We found no BeatMind watermark or credentials in this file. That does not prove it "
                                + "was not made with BeatMind: heavy editing can remove a watermark."));
                if (c2pa.path("present").asBoolean(false) && c2pa.path("aiGenerated").asBoolean(false)) {
                    answer.put("otherCredentials", "The file has content credentials from another source that say it is AI-generated.");
                }
            }
        }
        jdbc.sql("UPDATE verify_check SET status = :s, result = CAST(:r AS jsonb), upload_key = NULL WHERE id = :id")
                .param("s", outcome).param("r", json.writeValueAsString(answer)).param("id", check.get()).update();
    }

    /** The coarse public record of a song (REQ-F-32). */
    private Map<String, Object> describe(UUID songId, String how) {
        record S(java.time.OffsetDateTime createdAt, boolean instrumental, String rights) {}
        S s = jdbc.sql("SELECT created_at, instrumental, rights_status FROM song WHERE id = :id").param("id", songId)
                .query((rs, n) -> new S(rs.getObject(1, java.time.OffsetDateTime.class), rs.getBoolean(2), rs.getString(3)))
                .single();
        List<String> types = jdbc.sql("SELECT DISTINCT type FROM provenance_event WHERE song_id = :id")
                .param("id", songId).query(String.class).list();
        String lyrics = s.instrumental() ? "none"
                : types.contains("lyrics_written") || types.contains("lyrics_edited") ? "person" : "ai";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", "beatmind");
        out.put("match", how);
        out.put("madeOn", DAY.format(s.createdAt().atZoneSameInstant(IST)));
        out.put("aiGenerated", true);
        out.put("music", "ai");
        out.put("lyrics", lyrics);
        out.put("edited", types.contains("take_edited"));
        out.put("removed", "disabled".equals(s.rights()));
        out.put("message", "This music was made with BeatMind AI and is AI-generated.");
        return out;
    }

    private Check save(String kind, String sha, String key, UUID job, String status, Map<String, Object> result, String ip) {
        return save(UUID.randomUUID(), kind, sha, key, job, status, result, ip);
    }

    private Check save(UUID id, String kind, String sha, String key, UUID job, String status, Map<String, Object> result,
                       String ip) {
        jdbc.sql("""
                INSERT INTO verify_check (id, kind, file_sha256, upload_key, job_id, status, result, client_ip_hash)
                VALUES (:id, :kind, :sha, :key, :job, :status, CAST(:result AS jsonb), :ip)
                """).param("id", id).param("kind", kind).param("sha", sha).param("key", key).param("job", job)
                .param("status", status).param("result", result == null ? null : json.writeValueAsString(result))
                .param("ip", ip).update();
        return new Check(id, status, result);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
