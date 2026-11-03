package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** US-032: takedown orders and reports with deadline clocks, and appeals (REQ-F-54, REQ-F-59). */
@DatabaseTest
@AutoConfigureMockMvc
class TakedownTest extends SongApiSupport {

    @Autowired
    Takedowns takedowns;

    Cookie owner;
    Cookie admin;
    UUID song;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        owner = adult("owner@example.com");
        admin = SignInHelper.signInAdmin(mvc, "admin@example.com");
        UUID[] ids = made(owner, "monsoon lofi");
        workerFinishes(ids[0], false);
        song = ids[1];
    }

    private ResultActions report(Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", "https://beatmind.ai/en/songs/" + song);
        body.put("reporterKind", "rights_holder");
        body.put("reporterName", "Rights Holder Ltd");
        body.put("reporterEmail", "legal@rights.example");
        body.put("rightClaimed", "copyright");
        body.put("description", "This copies our recording.");
        body.put("accurate", true);
        body.putAll(overrides);
        // Signed out: rights holders need no account.
        return mvc.perform(post("/v1/reports").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions admin(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private UUID reported(Map<String, Object> overrides) throws Exception {
        String body = report(overrides).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    @Test
    @Tag("TC-REQ-F-54-01")
    void courtOrderRunsTheThreeHourClockEndToEnd() throws Exception {
        String body = report(Map.of("reporterKind", "court_order", "orderReference", "CS(COMM) 123/2026"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.songFound").value(true))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("TD-")))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());
        Takedowns.Takedown t = takedowns.find(id).orElseThrow();
        assertThat(Duration.between(t.createdAt(), t.actionDueAt())).isEqualTo(Duration.ofHours(3));
        assertThat(Duration.between(t.createdAt(), t.ackDueAt())).isEqualTo(Duration.ofHours(3));
        assertThat(t.clock()).isEqualTo("acknowledge");

        // The queue shows it first, with the deadline; nothing is overdue yet.
        mvc.perform(get("/v1/admin/takedowns").cookie(admin))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].overdue").value(false));
        // Past the deadline it is flagged and counted for the alert.
        jdbc.sql("UPDATE takedown_request SET ack_due_at = now() - interval '1 minute', action_due_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", id).update();
        mvc.perform(get("/v1/admin/takedowns").cookie(admin)).andExpect(jsonPath("$[0].overdue").value(true));
        mvc.perform(get("/v1/admin/takedowns/overdue").cookie(admin))
                .andExpect(jsonPath("$.acknowledge").value(1)).andExpect(jsonPath("$.act").value(1));

        admin("/v1/admin/takedowns/" + id + "/acknowledge", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("acknowledged")).andExpect(jsonPath("$.clock").value("act"));
        admin("/v1/admin/takedowns/" + id + "/decision", Map.of("decision", "disable", "note", "Valid court order."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("actioned"))
                .andExpect(jsonPath("$.overdue").value(false));

        // The song is down: no downloads, no licence, the owner sees why.
        mvc.perform(get("/v1/songs/" + song).cookie(owner))
                .andExpect(jsonPath("$.rightsStatus").value("disabled"))
                .andExpect(jsonPath("$.downloadFormats.length()").value(0))
                .andExpect(jsonPath("$.takedown.status").value("actioned"))
                .andExpect(jsonPath("$.takedown.rightClaimed").value("copyright"))
                .andExpect(jsonPath("$.takedown.reporterEmail").doesNotExist());
        mvc.perform(post("/v1/songs/" + song + "/takes/1/downloads").cookie(owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"mp3\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(owner)).andExpect(status().isForbidden());
        assertThat(events(song)).contains("takedown_received", "takedown_actioned");
        assertThat(jdbc.sql("SELECT count(*) FROM admin_audit WHERE action LIKE 'takedown_%'").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void ordinaryReportHasA24HourAcknowledgementAnd15DaysToAct() throws Exception {
        UUID id = reported(Map.of());
        Takedowns.Takedown t = takedowns.find(id).orElseThrow();
        assertThat(Duration.between(t.createdAt(), t.ackDueAt())).isEqualTo(Duration.ofHours(24));
        assertThat(Duration.between(t.createdAt(), t.actionDueAt())).isEqualTo(Duration.ofDays(15));
        admin("/v1/admin/takedowns/" + id + "/decision", Map.of("decision", "reject", "note", "No evidence."))
                .andExpect(jsonPath("$.status").value("rejected"));
        mvc.perform(get("/v1/songs/" + song).cookie(owner)).andExpect(jsonPath("$.rightsStatus").value("bm_owned"))
                .andExpect(jsonPath("$.takedown").doesNotExist());
    }

    @Test
    void reportsFindTheSongByCertificateOrShareLink() throws Exception {
        String cert = json.readTree(mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(owner))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        assertThat(takedowns.resolve("Certificate " + cert.toLowerCase()).songId()).isEqualTo(song);
        String url = json.readTree(mvc.perform(post("/v1/songs/" + song + "/shares").cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"public\"}"))
                .andReturn().getResponse().getContentAsString()).get("url").asString();
        assertThat(takedowns.resolve("https://beatmind.ai" + url).songId()).isEqualTo(song);
        report(Map.of("target", "a song somewhere")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.songFound").value(false));
        report(Map.of("accurate", false)).andExpect(status().isBadRequest());
        report(Map.of("reporterKind", "neighbour")).andExpect(status().isBadRequest());
    }

    @Test
    @Tag("TC-REQ-F-59-02")
    void ownerAppealsAndAPersonRestoresTheSongWithin72Hours() throws Exception {
        String cert = json.readTree(mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(owner))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        UUID id = reported(Map.of("rightClaimed", "voice"));
        admin("/v1/admin/takedowns/" + id + "/decision", Map.of("decision", "disable")).andExpect(status().isOk());
        mvc.perform(get("/v1/certificates/" + cert)).andExpect(jsonPath("$.status").value("revoked"));

        mvc.perform(post("/v1/songs/" + song + "/takedown/appeal").cookie(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"I sang the reference myself; here is the session file.\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("appealed"));
        // Once only.
        mvc.perform(post("/v1/songs/" + song + "/takedown/appeal").cookie(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"again\"}")).andExpect(status().isConflict());
        Takedowns.Takedown t = takedowns.find(id).orElseThrow();
        assertThat(t.clock()).isEqualTo("appeal");
        assertThat(Duration.between(t.appealedAt(), t.appealDueAt())).isEqualTo(Duration.ofHours(72));
        assertThat(t.dueAt()).isAfter(OffsetDateTime.now().plusHours(71));

        admin("/v1/admin/takedowns/" + id + "/appeal-decision", Map.of("decision", "restore", "note", "Own voice."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("restored"));
        mvc.perform(get("/v1/songs/" + song).cookie(owner))
                .andExpect(jsonPath("$.rightsStatus").value("free_licensed"))
                .andExpect(jsonPath("$.takedown.status").value("restored"));
        mvc.perform(get("/v1/certificates/" + cert)).andExpect(jsonPath("$.status").value("valid"));
        assertThat(events(song)).contains("takedown_appealed", "takedown_restored");
        // Another user cannot appeal it.
        Cookie other = adult("other@example.com");
        mvc.perform(post("/v1/songs/" + song + "/takedown/appeal").cookie(other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void reportsAreRateLimitedPerNetworkAndAdminOnlyForTheQueue() throws Exception {
        for (int i = 0; i < Takedowns.REPORTS_PER_IP_PER_HOUR; i++) {
            report(Map.of()).andExpect(status().isAccepted());
        }
        report(Map.of()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("velocity"));
        mvc.perform(get("/v1/admin/takedowns").cookie(owner)).andExpect(status().isForbidden());
    }
}
