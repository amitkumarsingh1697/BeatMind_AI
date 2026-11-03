package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** US-038: edits make new versions of a take; any version can be compared and restored (REQ-F-10, REQ-F-11). */
@DatabaseTest
@AutoConfigureMockMvc
class EditTest extends SongApiSupport {

    Cookie dev;
    UUID song;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        dev = adult("editor@example.com");
        UUID[] ids = made(dev, "monsoon lofi with flute");
        workerFinishes(ids[0], false);
        song = ids[1];
    }

    private ResultActions edit(int take, String op, Map<String, Object> params) throws Exception {
        return mvc.perform(post("/v1/songs/" + song + "/edits").cookie(dev).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("take", take, "op", op, "params", params))));
    }

    private UUID editJob() {
        return jdbc.sql("SELECT job_id FROM take_version WHERE status = 'processing'").query(UUID.class).single();
    }

    /** What the worker returns for an edit: the new files, and the seam check for AI edits. */
    private void workerEdits(UUID job, int version, String seam) {
        String result = """
                {"takes":[{"runner":"stub","rawKey":"edits/raw-v%1$d.wav","watermarkId":1001,"durationSeconds":42.5,"c2pa":true,
                  "assets":{"mp3_192":{"key":"edits/v%1$d-192.mp3","contentType":"audio/mpeg","sha256":"e%1$d","bytes":9},
                            "mp3_320":{"key":"edits/v%1$d-320.mp3","contentType":"audio/mpeg","sha256":"f%1$d","bytes":9},
                            "wav":{"key":"edits/v%1$d.wav","contentType":"audio/wav","sha256":"g%1$d","bytes":9}}%2$s}]}
                """.formatted(version, seam == null ? "" : ",\"seam\":" + seam);
        jdbc.sql("UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:r AS jsonb) WHERE id = :id")
                .param("r", result).param("id", job).update();
        finalizer.runOnce();
    }

    private int credits() throws Exception {
        return json.readTree(mvc.perform(get("/v1/credits").cookie(dev)).andReturn().getResponse().getContentAsString())
                .path("allowance").path("remaining").asInt();
    }

    @Test
    @Tag("TC-REQ-F-10-01")
    void trimMakesAVersionThePlayerAndDownloadsUse() throws Exception {
        edit(1, "trim", Map.of("start", 2, "end", 40)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.parent").value(1));
        UUID job = editJob();
        var payload = json.readTree(jdbc.sql("SELECT payload::text FROM generation_job WHERE id = :id").param("id", job)
                .query(String.class).single());
        assertThat(payload.get("sourceKey").asString()).endsWith("take-1.wav");
        assertThat(payload.get("params").get("end").asDouble()).isEqualTo(40.0);
        // One edit at a time per take.
        edit(1, "fade", Map.of("fadeOut", 3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("edit_in_progress"));

        workerEdits(job, 2, null);
        mvc.perform(get("/v1/songs/" + song).cookie(dev)).andExpect(jsonPath("$.takes[0].version").value(2))
                .andExpect(jsonPath("$.takes[1].version").value(1));
        mvc.perform(get("/v1/songs/" + song + "/takes/1/versions").cookie(dev))
                .andExpect(jsonPath("$[0].op").value("original")).andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[1].op").value("trim")).andExpect(jsonPath("$[1].current").value(true))
                .andExpect(jsonPath("$[1].durationSeconds").value(42.5))
                .andExpect(jsonPath("$[1].audioUrl").value("/v1/songs/" + song + "/takes/1/versions/2/audio"));
        mvc.perform(get("/v1/songs/" + song + "/takes/1/versions/1/audio").cookie(dev)).andExpect(status().isOk());
        // Downloads and the take audio now come from the edited files.
        String take = jdbc.sql("SELECT assets::text FROM take WHERE song_id = :s AND idx = 1").param("s", song)
                .query(String.class).single();
        assertThat(take).contains("edits/v2-192.mp3");
        assertThat(events(song)).contains("edit_requested,take_edited");
        assertThat(jdbc.sql("SELECT actor FROM provenance_event WHERE song_id = :s AND type = 'take_edited'")
                .param("s", song).query(String.class).single()).isEqualTo("human");
    }

    @Test
    @Tag("TC-REQ-F-11-01")
    void anEarlierVersionCanBeRestoredAndTheNextEditStartsFromIt() throws Exception {
        edit(1, "fade", Map.of("fadeIn", 2, "fadeOut", 4)).andExpect(status().isAccepted());
        workerEdits(editJob(), 2, null);
        mvc.perform(post("/v1/songs/" + song + "/takes/1/versions/1/restore").cookie(dev)).andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true));
        mvc.perform(get("/v1/songs/" + song).cookie(dev)).andExpect(jsonPath("$.takes[0].version").value(1));
        assertThat(jdbc.sql("SELECT assets::text FROM take WHERE song_id = :s AND idx = 1").param("s", song)
                .query(String.class).single()).contains("take-1-192.mp3");
        edit(1, "trim", Map.of("start", 0, "end", 30)).andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.parent").value(1));
        assertThat(events(song)).contains("version_restored");
        mvc.perform(post("/v1/songs/" + song + "/takes/1/versions/9/restore").cookie(dev)).andExpect(status().isNotFound());
    }

    @Test
    void aiEditsCostACreditThatIsRefundedIfTheyFail() throws Exception {
        int before = credits();
        edit(2, "regenerate", Map.of("start", 10, "end", 20)).andExpect(status().isAccepted());
        assertThat(credits()).isEqualTo(before - 1);
        workerFails(editJob());
        assertThat(credits()).isEqualTo(before);
        assertThat(events(song)).contains("edit_failed");
        mvc.perform(get("/v1/songs/" + song + "/takes/2/versions").cookie(dev)).andExpect(jsonPath("$[1].status").value("failed"));

        edit(2, "extend", Map.of("seconds", 15)).andExpect(status().isAccepted());
        workerEdits(editJob(), 3, "{\"seams\":[{\"atSeconds\":59.9,\"ratio\":1.1}],\"worstRatio\":1.1,\"limit\":3.0,\"passed\":true}");
        assertThat(credits()).isEqualTo(before - 1);
        assertThat(jdbc.sql("""
                SELECT actor || ':' || (body::jsonb -> 'payload' ->> 'seamPassed') FROM provenance_event
                 WHERE song_id = :s AND type = 'take_edited'
                """).param("s", song).query(String.class).single()).isEqualTo("ai:true");
        mvc.perform(get("/v1/songs/" + song + "/takes/2/versions").cookie(dev))
                .andExpect(jsonPath("$[2].seam.passed").value(true));
    }

    @Test
    void editsAreChecked() throws Exception {
        edit(1, "trim", Map.of("start", 10, "end", 12)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_edit"));
        edit(1, "trim", Map.of("start", 0, "end", 999)).andExpect(status().isBadRequest());
        edit(1, "fade", Map.of("fadeIn", 0, "fadeOut", 0)).andExpect(status().isBadRequest());
        edit(1, "fade", Map.of("fadeIn", 11)).andExpect(status().isBadRequest());
        edit(1, "extend", Map.of("seconds", 2)).andExpect(status().isBadRequest());
        edit(1, "regenerate", Map.of("start", 0, "end", 45)).andExpect(status().isBadRequest());
        edit(1, "reverse", Map.of()).andExpect(status().isBadRequest());
        edit(3, "trim", Map.of("start", 0, "end", 30)).andExpect(status().isNotFound());
        Cookie other = adult("other@example.com");
        mvc.perform(post("/v1/songs/" + song + "/edits").cookie(other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"take\":1,\"op\":\"trim\",\"params\":{\"start\":0,\"end\":30}}")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/songs/" + song + "/takes/1/versions").cookie(other)).andExpect(status().isNotFound());
    }
}
