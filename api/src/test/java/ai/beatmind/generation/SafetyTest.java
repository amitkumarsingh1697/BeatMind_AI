package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

/**
 * US-028 and US-029 through the API: blocked requests say why and open a case; the creator can
 * appeal; an admin decides in the queue; similarity-withheld takes are logged, refunded and can
 * be released (REQ-F-50 to REQ-F-52, REQ-F-59).
 */
@DatabaseTest
@AutoConfigureMockMvc
class SafetyTest extends SongApiSupport {

    Cookie neha;
    Cookie admin;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        neha = adult("neha@example.com");
        admin = SignInHelper.signInAdmin(mvc, "admin@example.com");
    }

    @Test
    @Tag("TC-REQ-F-50-01")
    void artistRequestIsBlockedWithAReasonAndNothingIsQueued() throws Exception {
        String body = request(neha, Map.of("kind", "song", "prompt", "sad song in the voice of अरिजीत सिंह", "language", "hi"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("moderation_artist"))
                .andExpect(jsonPath("$.field").value("prompt"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("real artists")))
                .andExpect(jsonPath("$.caseId").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.sql("SELECT count(*) FROM generation_job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM credit_ledger").query(Long.class).single()).isZero();
        // The blocked text is not stored, only its hash.
        String stored = jdbc.sql("SELECT category || ':' || rule || ':' || length(text_sha256) FROM moderation_case")
                .query(String.class).single();
        assertThat(stored).isEqualTo("artist:artist:arijit sinh:64");
        assertThat(body).doesNotContain("text_sha256");
    }

    @Test
    @Tag("TC-REQ-F-51-01")
    void lyricsWithAThreatAreBlocked() throws Exception {
        request(neha, Map.of("kind", "song", "mode", "lyrics", "prompt", "punjabi drill",
                "lyrics", "[Verse]\nmain tujhe maar dalunga\n", "language", "hinglish"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("moderation_threat"))
                .andExpect(jsonPath("$.field").value("lyrics"));
    }

    @Test
    void renamingToABlockedTitleIsRefused() throws Exception {
        UUID[] ids = made(neha, "monsoon lofi");
        mvc.perform(patch("/v1/songs/" + ids[1]).cookie(neha).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"kill all the muslims\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("moderation_hate"));
    }

    @Test
    @Tag("TC-REQ-F-59-01")
    void appealGoesToTheAdminQueueAndAnOverturnLetsTheSameRequestThrough() throws Exception {
        Map<String, Object> req = Map.of("kind", "song", "prompt", "rap like Badshah about Delhi nights", "language", "hinglish");
        String caseId = json.readTree(request(neha, req).andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString()).get("caseId").asString();

        // Someone else cannot appeal it; the owner can, once.
        Cookie other = adult("other@example.com");
        appeal(other, caseId).andExpect(status().isConflict());
        appeal(neha, caseId).andExpect(status().isAccepted());
        appeal(neha, caseId).andExpect(status().isConflict());

        mvc.perform(get("/v1/admin/moderation").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(caseId))
                .andExpect(jsonPath("$[0].status").value("appealed"))
                .andExpect(jsonPath("$[0].appealNote").value("I meant the word for king"));
        mvc.perform(get("/v1/admin/moderation").cookie(neha)).andExpect(status().isForbidden());

        mvc.perform(post("/v1/admin/moderation/" + caseId + "/decision").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"overturn\",\"note\":\"Rapper reference is about a king here\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("overturned"));
        mvc.perform(get("/v1/moderation/cases").cookie(neha))
                .andExpect(jsonPath("$[0].status").value("overturned"))
                .andExpect(jsonPath("$[0].decisionNote").value("Rapper reference is about a king here"));
        assertThat(jdbc.sql("SELECT action FROM admin_audit WHERE target = :t").param("t", caseId).query(String.class).single())
                .isEqualTo("moderation_overturn");

        request(neha, req).andExpect(status().isAccepted());
        // A different text is still screened.
        request(neha, Map.of("kind", "song", "prompt", "sing like adele", "language", "en"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @Tag("TC-REQ-F-52-01")
    void similarTakeIsWithheldLoggedRefundedAndReleasedOnReview() throws Exception {
        UUID[] ids = made(neha, "monsoon lofi with flute");
        workerFinishesWithSimilarMatch(ids[0]);

        mvc.perform(get("/v1/songs/" + ids[1]).cookie(neha))
                .andExpect(jsonPath("$.takes[1].status").value("withheld"))
                .andExpect(jsonPath("$.takes[1].audioUrl").doesNotExist());
        assertThat(events(ids[1])).contains("take_withheld").endsWith("credits_refunded");
        assertThat(jdbc.sql("SELECT screen->>'score' FROM take WHERE song_id = :s AND idx = 2").param("s", ids[1])
                .query(String.class).single()).isEqualTo("0.62");

        mvc.perform(post("/v1/songs/" + ids[1] + "/takes/2/appeal").cookie(neha).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"This is my own melody\"}"))
                .andExpect(status().isAccepted());
        String caseId = json.readTree(mvc.perform(get("/v1/admin/moderation").cookie(admin))
                .andExpect(jsonPath("$[0].kind").value("similarity"))
                .andExpect(jsonPath("$[0].takeIdx").value(2))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asString();
        mvc.perform(get("/v1/admin/songs/" + ids[1] + "/takes/2/audio").cookie(admin)).andExpect(status().isOk());

        mvc.perform(post("/v1/admin/moderation/" + caseId + "/decision").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"overturn\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/songs/" + ids[1]).cookie(neha))
                .andExpect(jsonPath("$.takes[1].status").value("ready"))
                .andExpect(jsonPath("$.takes[1].audioUrl").exists());
        assertThat(events(ids[1])).endsWith("appeal_requested,take_released");
    }

    private org.springframework.test.web.servlet.ResultActions appeal(Cookie who, String caseId) throws Exception {
        return mvc.perform(post("/v1/moderation/cases/" + caseId + "/appeal").cookie(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"I meant the word for king\"}"));
    }
}
