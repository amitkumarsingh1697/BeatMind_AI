package ai.beatmind.generation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Shared set-up for the Sprint 2 song tests: sign-in, requests and a fake worker. */
abstract class SongApiSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobFinalizer finalizer;

    @Autowired
    JsonMapper json;

    @MockitoBean
    AudioStore audio;

    void resetAll() {
        SignInHelper.reset(jdbc);
        Mockito.when(audio.open(Mockito.anyString())).thenAnswer(inv -> {
            byte[] bytes = ("ID3 audio for " + inv.getArgument(0)).getBytes();
            String type = inv.getArgument(0, String.class).endsWith(".wav") ? "audio/wav" : "audio/mpeg";
            return Optional.of(new AudioStore.Audio(new ByteArrayInputStream(bytes), type, bytes.length));
        });
    }

    Cookie adult(String email) throws Exception {
        return SignInHelper.signInAdult(mvc, email);
    }

    void setPlan(String email, String plan) {
        jdbc.sql("UPDATE app_user SET plan = :plan WHERE email = :email").param("plan", plan).param("email", email).update();
    }

    ResultActions request(Cookie cookie, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/v1/generations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    ResultActions vibe(Cookie cookie, String prompt) throws Exception {
        return request(cookie, Map.of("kind", "song", "prompt", prompt, "language", "hi"));
    }

    /** Creates a song and returns {jobId, songId}. */
    UUID[] made(Cookie cookie, Map<String, Object> body) throws Exception {
        String response = request(cookie, body).andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString();
        var node = json.readTree(response);
        return new UUID[] {UUID.fromString(node.get("id").asString()), UUID.fromString(node.get("songId").asString())};
    }

    UUID[] made(Cookie cookie, String prompt) throws Exception {
        return made(cookie, Map.of("kind", "song", "prompt", prompt, "language", "hi"));
    }

    /** What the Sprint 2 worker writes: labelled MP3 192, MP3 320 and WAV per take. */
    void workerFinishes(UUID jobId, boolean withholdSecond) {
        workerFinishes(jobId, withholdSecond ? "\"withheld\":true," : "");
    }

    /** As above, with the similarity screen withholding the second take (US-029). */
    void workerFinishesWithSimilarMatch(UUID jobId) {
        workerFinishes(jobId, """
                "withheld":true,"withheldReason":"similarity",
                "similarity":{"checked":true,"score":0.62,"matched":140,"referenceId":7,"referenceTitle":"Known song"},""");
    }

    void workerFinishes(UUID jobId, String secondExtra) {
        String take = """
                {"key":"takes/%1$s/take-%2$d.wav","format":"wav","runner":"stub","watermarkId":%3$d,"c2pa":true,%4$s
                 "assets":{"mp3_192":{"key":"takes/%1$s/take-%2$d-192.mp3","contentType":"audio/mpeg","sha256":"a%2$d","bytes":10},
                           "mp3_320":{"key":"takes/%1$s/take-%2$d-320.mp3","contentType":"audio/mpeg","sha256":"b%2$d","bytes":10},
                           "wav":{"key":"takes/%1$s/take-%2$d.wav","contentType":"audio/wav","sha256":"c%2$d","bytes":10}}}
                """;
        String result = "{\"takes\":[" + take.formatted(jobId, 1, 1001, "") + ","
                + take.formatted(jobId, 2, 1002, secondExtra) + "]}";
        jdbc.sql("UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:r AS jsonb) WHERE id = :id")
                .param("r", result)
                .param("id", jobId)
                .update();
        finalizer.runOnce();
    }

    void workerFails(UUID jobId) {
        jdbc.sql("UPDATE generation_job SET status = 'dead', attempts = 3, finished_at = now(), error = 'boom' WHERE id = :id")
                .param("id", jobId)
                .update();
        finalizer.runOnce();
    }

    /** The song's ledger event types in order, comma-separated. */
    String events(UUID song) {
        return jdbc.sql("SELECT string_agg(type, ',' ORDER BY seq) FROM provenance_event WHERE song_id = :song")
                .param("song", song).query(String.class).single();
    }

    /** A fake lyric worker: finishes the lyrics job with the given text. */
    void lyricWorkerWrites(UUID draftId, String lyrics) {
        UUID job = jdbc.sql("SELECT job_id FROM lyric_draft WHERE id = :id").param("id", draftId).query(UUID.class).single();
        jdbc.sql("UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:r AS jsonb) WHERE id = :id")
                .param("r", json.writeValueAsString(java.util.Map.of("lyrics", lyrics, "model", "test-writer")))
                .param("id", job)
                .update();
        finalizer.runOnce();
    }
}
