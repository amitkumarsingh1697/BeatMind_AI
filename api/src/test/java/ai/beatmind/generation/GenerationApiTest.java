package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** US-012: API-001 and API-002, from request to two playable takes. US-013 dead-letter view. */
@DatabaseTest
@AutoConfigureMockMvc
class GenerationApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    AudioStore audio;

    Cookie asha;

    @BeforeEach
    void setUp() throws Exception {
        SignInHelper.reset(jdbc);
        asha = SignInHelper.signInAdult(mvc, "asha@example.com");
    }

    @Test
    void songRequestIsQueuedForTwoTakes() throws Exception {
        String id = create(asha, "lofi chai morning, soft female vocals")
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.prompt").value("lofi chai morning, soft female vocals"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        var row = jdbc.sql("""
                SELECT kind, status, payload->>'takes', payload->>'language', user_id IS NOT NULL
                  FROM generation_job WHERE id = :id
                """)
                .param("id", UUID.fromString(id))
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getString(4) + "|" + rs.getBoolean(5))
                .single();
        assertThat(row).isEqualTo("song|queued|2|hi|true");
    }

    @Test
    void profileMustBeCompleteFirst() throws Exception {
        Cookie newcomer = SignInHelper.signIn(mvc, "new@example.com");

        create(newcomer, "anything").andExpect(status().isForbidden());
    }

    @Test
    void longPromptIsRefusedButDevanagariCountsByCharacter() throws Exception {
        create(asha, "a".repeat(501)).andExpect(status().isBadRequest());

        // "कि" is 2 code units but one user-perceived character: 300 of them fit in 500.
        String hindi = "कि".repeat(300);
        assertThat(hindi.length()).isGreaterThan(500);
        create(asha, hindi).andExpect(status().isAccepted());
    }

    @Test
    void onlyTwoSongsInProgressAtOnce() throws Exception {
        create(asha, "one").andExpect(status().isAccepted());
        create(asha, "two").andExpect(status().isAccepted());

        create(asha, "three").andExpect(status().isTooManyRequests());
    }

    @Test
    void anotherUsersSongLooksMissing() throws Exception {
        String id = createdId(asha, "mine");
        Cookie ravi = SignInHelper.signInAdult(mvc, "ravi@example.com");

        mvc.perform(get("/v1/generations/" + id).cookie(ravi)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/generations/" + id + "/events").cookie(ravi)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/generations/" + id + "/takes/1/audio").cookie(ravi)).andExpect(status().isNotFound());
    }

    @Test
    void finishedSongHasTwoPlayableTakes() throws Exception {
        String id = createdId(asha, "monsoon bhangra");
        workerFinishes(id);
        given(audio.open(anyString())).willAnswer(inv -> Optional.of(
                new AudioStore.Audio(new ByteArrayInputStream("RIFFdata".getBytes()), "audio/wav", 8)));

        mvc.perform(get("/v1/generations/" + id).cookie(asha))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.takes.length()").value(2))
                .andExpect(jsonPath("$.takes[1].audioUrl").value("/v1/generations/" + id + "/takes/2/audio"));
        mvc.perform(get("/v1/generations/" + id + "/takes/2/audio").cookie(asha))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"))
                .andExpect(content().string("RIFFdata"));
        mvc.perform(get("/v1/generations/" + id + "/takes/3/audio").cookie(asha)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/generations").cookie(asha)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void eventsStreamSendsStatusUntilFinished() throws Exception {
        String id = createdId(asha, "monsoon bhangra");
        workerFinishes(id);

        MvcResult started = mvc.perform(get("/v1/generations/" + id + "/events").cookie(asha))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:status").contains("\"status\":\"succeeded\"").contains("takes/2/audio");
    }

    @Test
    void eventsStreamFollowsAJobInProgress() throws Exception {
        String id = createdId(asha, "monsoon bhangra");
        MvcResult started = mvc.perform(get("/v1/generations/" + id + "/events").cookie(asha))
                .andExpect(request().asyncStarted())
                .andReturn();
        Thread.sleep(200);
        workerFinishes(id);

        String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

        assertThat(body.indexOf("\"status\":\"queued\"")).isLessThan(body.indexOf("\"status\":\"succeeded\""));
    }

    @Test
    void deadJobShowsAsFailedAndAdminCanRetryIt() throws Exception {
        String id = createdId(asha, "will fail");
        jdbc.sql("UPDATE generation_job SET status = 'dead', attempts = 3, error = 'RuntimeError: CUDA out of memory' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();

        mvc.perform(get("/v1/generations/" + id).cookie(asha))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("CUDA"))));

        Cookie admin = SignInHelper.signIn(mvc, "admin@example.com");
        mvc.perform(get("/v1/admin/jobs").cookie(admin))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].error").value("RuntimeError: CUDA out of memory"));
        mvc.perform(post("/v1/admin/jobs/" + id + "/retry").cookie(admin)).andExpect(status().isAccepted());

        assertThat(jdbc.sql("SELECT status || '|' || attempts FROM generation_job WHERE id = :id")
                .param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo("queued|0");
        assertThat(jdbc.sql("SELECT action || '|' || target FROM admin_audit").query(String.class).single())
                .isEqualTo("job.retry|generation_job:" + id);
        mvc.perform(post("/v1/admin/jobs/" + id + "/retry").cookie(admin)).andExpect(status().isNotFound());
    }

    private ResultActions create(Cookie cookie, String prompt) throws Exception {
        return mvc.perform(post("/v1/generations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"song\",\"prompt\":\"%s\",\"language\":\"hi\"}".formatted(prompt)));
    }

    private String createdId(Cookie cookie, String prompt) throws Exception {
        String body = create(cookie, prompt).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    /** What the worker writes when both takes are stored. */
    private void workerFinishes(String id) {
        jdbc.sql("""
                UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:result AS jsonb)
                 WHERE id = :id
                """)
                .param("id", UUID.fromString(id))
                .param("result", """
                        {"takes":[{"key":"takes/%1$s/take-1.wav","format":"wav"},
                                  {"key":"takes/%1$s/take-2.wav","format":"wav"}],"seconds":4.2}
                        """.formatted(id))
                .update();
    }
}
