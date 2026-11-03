package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** US-021: the public verify page, by file or certificate ID, with coarse anonymous answers (REQ-F-32, REQ-SEC-10). */
@DatabaseTest
@AutoConfigureMockMvc
class VerifyTest extends SongApiSupport {

    Cookie owner;
    UUID song;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        owner = adult("owner@example.com");
        UUID[] ids = made(owner, "secret prompt about my village");
        workerFinishes(ids[0], false);
        song = ids[1];
    }

    private ResultActions upload(byte[] data) throws Exception {
        return mvc.perform(multipart("/v1/verify").file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", data)));
    }

    @Test
    @Tag("TC-REQ-F-32-01")
    void aFileWeStoredIsRecognisedWithoutRevealingWhoMadeIt() throws Exception {
        byte[] file = "exact bytes of the labelled mp3".getBytes(StandardCharsets.UTF_8);
        jdbc.sql("UPDATE take SET assets = jsonb_set(assets, '{mp3_192,sha256}', to_jsonb(CAST(:sha AS text))) WHERE song_id = :s AND idx = 1")
                .param("sha", Verifier.sha256(file)).param("s", song).update();
        String body = upload(file).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.result.verdict").value("beatmind"))
                .andExpect(jsonPath("$.result.match").value("exact_file"))
                .andExpect(jsonPath("$.result.aiGenerated").value(true))
                .andExpect(jsonPath("$.result.lyrics").value("ai"))
                .andExpect(jsonPath("$.result.madeOn").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("owner@example.com").doesNotContain("village").doesNotContain(song.toString());
        // Nothing was uploaded for an exact match.
        Mockito.verify(audio, Mockito.never()).put(Mockito.anyString(), Mockito.any(), Mockito.anyString());
    }

    @Test
    void anUnknownFileGoesToAWorkerWhichFindsTheWatermark() throws Exception {
        String body = upload("re-encoded copy".getBytes(StandardCharsets.UTF_8)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("checking")).andReturn().getResponse().getContentAsString();
        UUID check = UUID.fromString(json.readTree(body).get("id").asString());
        Mockito.verify(audio).put(Mockito.eq("verify/" + check), Mockito.any(), Mockito.anyString());
        UUID job = jdbc.sql("SELECT job_id FROM verify_check WHERE id = :id").param("id", check).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT kind FROM generation_job WHERE id = :id").param("id", job).query(String.class).single())
                .isEqualTo("verify");
        jdbc.sql("""
                UPDATE generation_job SET status = 'succeeded', finished_at = now(),
                       result = '{"verify":{"format":"mp3","watermark":{"found":true,"payload":1002,"score":14.2},"c2pa":{"present":false}}}'
                 WHERE id = :id
                """).param("id", job).update();
        finalizer.runOnce();
        mvc.perform(get("/v1/verify/" + check)).andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.result.verdict").value("beatmind"))
                .andExpect(jsonPath("$.result.match").value("watermark"));
        assertThat(jdbc.sql("SELECT upload_key FROM verify_check WHERE id = :id").param("id", check)
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void noMarkMeansNotFoundWithACaveat() throws Exception {
        String body = upload("someone else's song".getBytes(StandardCharsets.UTF_8)).andReturn().getResponse().getContentAsString();
        UUID check = UUID.fromString(json.readTree(body).get("id").asString());
        UUID job = jdbc.sql("SELECT job_id FROM verify_check WHERE id = :id").param("id", check).query(UUID.class).single();
        jdbc.sql("""
                UPDATE generation_job SET status = 'succeeded', finished_at = now(),
                       result = '{"verify":{"format":"mp3","watermark":{"found":false,"score":2.1},"c2pa":{"present":false}}}'
                 WHERE id = :id
                """).param("id", job).update();
        finalizer.runOnce();
        mvc.perform(get("/v1/verify/" + check)).andExpect(jsonPath("$.result.verdict").value("not_found"))
                .andExpect(jsonPath("$.result.message").value(org.hamcrest.Matchers.containsString("does not prove")));
    }

    @Test
    void certificatesCanBeCheckedByIdAndChecksAreRateLimited() throws Exception {
        String cert = json.readTree(mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(owner))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/v1/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateId\":\"" + cert.toLowerCase() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.verdict").value("beatmind"))
                .andExpect(jsonPath("$.result.certificate.status").value("valid"))
                .andExpect(jsonPath("$.result.certificate.scope").value("commercial_free"));
        mvc.perform(post("/v1/verify").contentType(MediaType.APPLICATION_JSON).content("{\"certificateId\":\"BM-0000-0000-0000\"}"))
                .andExpect(jsonPath("$.result.verdict").value("not_found"));
        for (int i = 2; i < 20; i++) {
            mvc.perform(post("/v1/verify").contentType(MediaType.APPLICATION_JSON).content("{\"certificateId\":\"nope\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/v1/verify").contentType(MediaType.APPLICATION_JSON).content("{\"certificateId\":\"nope\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("velocity"));
        mvc.perform(get("/v1/verify/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
