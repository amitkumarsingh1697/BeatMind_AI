package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** US-039, REQ-F-23: four stems of a take for paid plans, finished, recorded and downloadable. */
@DatabaseTest
@AutoConfigureMockMvc
class StemsTest extends SongApiSupport {

    @Autowired
    AudioStore audio;

    Cookie dev;
    UUID song;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        dev = adult("producer@example.com");
        UUID[] ids = made(dev, "bhangra with dhol");
        workerFinishes(ids[0], false);
        song = ids[1];
    }

    private UUID stemsJob() {
        return jdbc.sql("SELECT job_id FROM take_stems WHERE status = 'processing'").query(UUID.class).single();
    }

    private void workerSeparates(UUID job) {
        StringBuilder assets = new StringBuilder();
        for (String n : Stems.NAMES) {
            String key = "stems/" + song + "/take-1-v1-" + n + ".wav";
            audio.put(key, ("RIFF" + n).getBytes(), "audio/wav");
            assets.append(assets.isEmpty() ? "" : ",").append("""
                    "%1$s":{"key":"%2$s","contentType":"audio/wav","sha256":"sha-%1$s","bytes":8}""".formatted(n, key));
        }
        String result = "{\"stems\":{" + assets + "},\"sumCheck\":{\"snrDb\":120.0,\"minSnrDb\":30.0,\"passed\":true},"
                + "\"model\":\"stub-bands\"}";
        jdbc.sql("UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:r AS jsonb) WHERE id = :id")
                .param("r", result).param("id", job).update();
        finalizer.runOnce();
    }

    @Test
    void stemsComeWithPaidPlans() throws Exception {
        mvc.perform(post("/v1/songs/" + song + "/takes/1/stems").cookie(dev))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan"));
        mvc.perform(get("/v1/songs/" + song + "/takes/1/stems").cookie(dev)).andExpect(status().isNotFound());
    }

    @Test
    @Tag("TC-REQ-F-23-04")
    void aPaidUserGetsFourStemsOfTheCurrentVersion() throws Exception {
        setPlan("producer@example.com", "creator");
        mvc.perform(post("/v1/songs/" + song + "/takes/1/stems").cookie(dev))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.version").value(1));
        UUID job = stemsJob();
        var payload = json.readTree(jdbc.sql("SELECT payload::text FROM generation_job WHERE id = :id").param("id", job)
                .query(String.class).single());
        assertThat(payload.get("sourceKey").asString()).endsWith("take-1.wav");
        assertThat(jdbc.sql("SELECT kind FROM generation_job WHERE id = :id").param("id", job).query(String.class).single())
                .isEqualTo("stems");
        // Asking again while it runs does not start a second job.
        mvc.perform(post("/v1/songs/" + song + "/takes/1/stems").cookie(dev)).andExpect(status().isAccepted());
        assertThat(jdbc.sql("SELECT count(*) FROM generation_job WHERE kind = 'stems'").query(Integer.class).single()).isEqualTo(1);

        workerSeparates(job);
        mvc.perform(get("/v1/songs/" + song + "/takes/1/stems").cookie(dev))
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.stems.length()").value(4))
                .andExpect(jsonPath("$.stems[0].name").value("vocals"))
                .andExpect(jsonPath("$.sumCheck.passed").value(true))
                .andExpect(jsonPath("$.model").value("stub-bands"));
        assertThat(jdbc.sql("SELECT string_agg(type, ',' ORDER BY seq) FROM provenance_event WHERE song_id = :s AND type LIKE 'stems%'")
                .param("s", song).query(String.class).single()).isEqualTo("stems_requested,stems_separated");
        assertThat(jdbc.sql("SELECT actor FROM provenance_event WHERE song_id = :s AND type = 'stems_separated'")
                .param("s", song).query(String.class).single()).isEqualTo("ai");

        String link = json.readTree(mvc.perform(post("/v1/songs/" + song + "/takes/1/stems/drums/downloads").cookie(dev))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.endsWith("-take-1-drums.wav")))
                        .andReturn().getResponse().getContentAsString())
                .get("url").asString();
        // The signed link works without a session, and only for stems.
        mvc.perform(get(link)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("drums.wav")));
        mvc.perform(get(link.replace("/downloads/stems/", "/downloads/"))).andExpect(status().isNotFound());
        mvc.perform(post("/v1/songs/" + song + "/takes/1/stems/guitar/downloads").cookie(dev)).andExpect(status().isNotFound());

        // REQ-F-58: after the plan ends, stems of a song the user owns stay downloadable.
        jdbc.sql("UPDATE song SET rights_status = 'user_owned' WHERE id = :s").param("s", song).update();
        setPlan("producer@example.com", "free");
        mvc.perform(post("/v1/songs/" + song + "/takes/1/stems/vocals/downloads").cookie(dev)).andExpect(status().isOk());
        // The public verify page recognises a stem file by its hash.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM take_stems st, jsonb_each(st.assets) a WHERE a.value->>'sha256' = 'sha-bass'
                """).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aFailedSeparationCanBeAskedForAgain() throws Exception {
        setPlan("producer@example.com", "pro");
        mvc.perform(post("/v1/songs/" + song + "/takes/1/stems").cookie(dev)).andExpect(status().isAccepted());
        UUID job = stemsJob();
        jdbc.sql("UPDATE generation_job SET status = 'dead', finished_at = now() WHERE id = :id").param("id", job).update();
        finalizer.runOnce();
        mvc.perform(get("/v1/songs/" + song + "/takes/1/stems").cookie(dev)).andExpect(jsonPath("$.status").value("failed"));
        mvc.perform(post("/v1/songs/" + song + "/takes/1/stems").cookie(dev))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("processing"));
        assertThat(stemsJob()).isNotEqualTo(job);
    }
}
