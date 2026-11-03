package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** US-025: free commercial licences (5 a month) and rights certificates (REQ-F-21, REQ-F-22, REQ-F-26). */
@DatabaseTest
@AutoConfigureMockMvc
class LicenceTest extends SongApiSupport {

    Cookie neha;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        neha = adult("neha@example.com");
    }

    private UUID finishedSong(Cookie who, String prompt) throws Exception {
        UUID[] ids = made(who, prompt);
        workerFinishes(ids[0], false);
        return ids[1];
    }

    @Test
    void licenceIssuesAPublicCertificateWithAttributionAndNoPersonalData() throws Exception {
        UUID song = finishedSong(neha, "monsoon lofi");
        String body = mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(neha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("commercial_free"))
                .andExpect(jsonPath("$.status").value("valid"))
                .andExpect(jsonPath("$.attribution").value(org.hamcrest.Matchers.startsWith("Music: made with BeatMind AI")))
                .andExpect(jsonPath("$.aiDisclosure").value("This song was generated with artificial intelligence."))
                .andExpect(jsonPath("$.masterSha256").value("c1"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();
        assertThat(id).matches("BM-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}");

        // Public, signed out; no email anywhere.
        String pub = mvc.perform(get("/v1/certificates/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountReference").value(org.hamcrest.Matchers.startsWith("BeatMind account ")))
                .andReturn().getResponse().getContentAsString();
        assertThat(pub).doesNotContain("neha@example.com");
        // REQ-F-26: never promises copyright.
        assertThat(pub).contains("not a certificate of copyright").doesNotContainIgnoringCase("guarantee");

        mvc.perform(get("/v1/songs/" + song).cookie(neha))
                .andExpect(jsonPath("$.rightsStatus").value("free_licensed"))
                .andExpect(jsonPath("$.certificateId").value(id));
        // Asking again returns the same certificate and does not use another licence.
        mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(neha))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/v1/licences/quota").cookie(neha)).andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.limit").value(5));
        assertThat(events(song)).endsWith("licence_issued");
    }

    @Test
    @Tag("TC-REQ-F-21-01")
    void sixthLicenceInAMonthIsRefused() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID song = finishedSong(neha, "song " + i);
            mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(neha)).andExpect(status().isCreated());
        }
        UUID sixth = finishedSong(neha, "song six");
        mvc.perform(post("/v1/songs/" + sixth + "/commercial-licence").cookie(neha))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("licence_quota"));
    }

    @Test
    @Tag("TC-REQ-F-21-02")
    void quotaCountsTheCalendarMonthInIndiaTime() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID song = finishedSong(neha, "song " + i);
            mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(neha)).andExpect(status().isCreated());
        }
        // Last month's licences do not count this month.
        jdbc.sql("UPDATE certificate SET quota_month = '2000-01'").update();
        UUID next = finishedSong(neha, "next month");
        mvc.perform(post("/v1/songs/" + next + "/commercial-licence").cookie(neha)).andExpect(status().isCreated());
    }

    @Test
    @Tag("TC-REQ-F-22-01")
    void paidSongGetsAnOwnershipCertificateWithoutUsingTheQuota() throws Exception {
        setPlan("neha@example.com", "creator");
        UUID song = finishedSong(neha, "paid song");
        mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(neha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("ownership_paid"))
                .andExpect(jsonPath("$.grant").value(org.hamcrest.Matchers.containsString("assigned to the account holder")))
                .andExpect(jsonPath("$.attribution").doesNotExist());
        mvc.perform(get("/v1/licences/quota").cookie(neha)).andExpect(jsonPath("$.used").value(0));
    }

    @Test
    void unfinishedOrOtherPeoplesSongsCannotBeLicensed() throws Exception {
        UUID[] pending = made(neha, "still running");
        mvc.perform(post("/v1/songs/" + pending[1] + "/commercial-licence").cookie(neha))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_ready"));
        mvc.perform(post("/v1/songs/" + pending[1] + "/commercial-licence").cookie(adult("other@example.com")))
                .andExpect(status().isNotFound());
    }
}
