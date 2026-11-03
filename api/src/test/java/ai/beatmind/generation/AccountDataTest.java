package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** US-011: export my data as JSON plus files, and delete my account (REQ-NFR-05, DPIA sections 6 and 7). */
@DatabaseTest
@AutoConfigureMockMvc
class AccountDataTest extends SongApiSupport {

    @Autowired
    SongPurge purge;

    Cookie neha;
    UUID song;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        neha = adult("neha@example.com");
        UUID[] ids = made(neha, "monsoon lofi with flute");
        workerFinishes(ids[0], false);
        song = ids[1];
    }

    record Zip(JsonNode data, List<String> names) {}

    private Zip export(Cookie who) throws Exception {
        MvcResult started = mvc.perform(get("/v1/me/export").cookie(who)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("beatmind-export-")))
                .andReturn();
        byte[] bytes = done.getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        JsonNode data = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
                if ("data.json".equals(e.getName())) {
                    data = json.readTree(zip.readAllBytes());
                }
            }
        }
        return new Zip(data, names);
    }

    @Test
    @Tag("TC-REQ-NFR-05-01")
    void exportHoldsEverythingAboutTheAccountAndTheFilesThePlanAllows() throws Exception {
        mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(neha)).andExpect(status().isCreated());
        Zip zip = export(neha);
        JsonNode data = zip.data();
        assertThat(data.get("format").asString()).isEqualTo("beatmind-export/1");
        assertThat(data.get("account").get("email").asString()).isEqualTo("neha@example.com");
        assertThat(data.get("account").get("birthMonth").asString()).isEqualTo("1995-04-01");
        JsonNode s = data.get("songs").get(0);
        assertThat(s.get("prompt").asString()).isEqualTo("monsoon lofi with flute");
        assertThat(s.get("label").asString()).isEqualTo("AI-generated");
        assertThat(s.get("history").size()).isGreaterThan(3);
        assertThat(data.get("credits").size()).isGreaterThan(0);
        assertThat(data.get("licences").size()).isEqualTo(1);
        // Free plan: the MP3 it can download, not the WAV.
        assertThat(zip.names()).contains("data.json", "README.txt")
                .anyMatch(n -> n.matches("songs/monsoon-lofi-with-flute-[0-9a-f]{8}/take-1\\.mp3"))
                .noneMatch(n -> n.endsWith(".wav"));
        assertThat(s.get("takes").get(0).get("files").get(0).asString()).endsWith("take-1.mp3");

        setPlan("neha@example.com", "creator");
        Cookie paid = SignInHelper.signIn(mvc, "neha@example.com");
        assertThat(export(paid).names()).anyMatch(n -> n.endsWith("take-1.wav"));
        // Someone else's export has none of it.
        Zip other = export(adult("other@example.com"));
        assertThat(other.data().get("songs").size()).isZero();
    }

    @Test
    @Tag("TC-REQ-NFR-05-02")
    void deletingTheAccountRemovesPersonalDataAndKeepsEvidence() throws Exception {
        String cert = json.readTree(mvc.perform(post("/v1/songs/" + song + "/commercial-licence").cookie(neha))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        String share = json.readTree(mvc.perform(post("/v1/songs/" + song + "/shares").cookie(neha)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"public\"}"))
                .andReturn().getResponse().getContentAsString()).get("url").asString();
        UUID pending = made(neha, "queued when the account closes")[0];
        // Needs the email typed back.
        mvc.perform(delete("/v1/me").cookie(neha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmEmail\":\"someone@example.com\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("confirm_email"));

        mvc.perform(delete("/v1/me").cookie(neha).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmEmail\":\"Neha@Example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.songs").value(2))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        // Signed out everywhere; no personal data left on the row.
        mvc.perform(get("/v1/me").cookie(neha)).andExpect(status().isUnauthorized());
        var row = jdbc.sql("SELECT email, google_sub, birth_month, deleted_at FROM app_user WHERE email LIKE 'deleted+%'")
                .query().singleRow();
        assertThat(row.get("email").toString()).endsWith("@deleted.invalid");
        assertThat(row.get("birth_month")).isNull();
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM user_session").query(Long.class).single()).isZero();
        // The share link stops, the queued job will not run.
        mvc.perform(get("/v1/shared/" + share.substring(share.lastIndexOf('/') + 1))).andExpect(status().isGone());
        assertThat(jdbc.sql("SELECT status FROM generation_job WHERE id = :id").param("id", pending)
                .query(String.class).single()).isEqualTo("dead");
        // The certificate still verifies for the people who rely on it, and the ledger chain holds.
        mvc.perform(get("/v1/certificates/" + cert)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("valid"));
        assertThat(events(song)).endsWith("deleted");
        // Files go 30 days later, with the purge job.
        jdbc.sql("UPDATE song SET deleted_at = now() - interval '31 days'").update();
        assertThat(purge.runOnce()).isEqualTo(2);
        // Signing in again with the same email starts a new, empty account.
        Cookie again = adult("neha@example.com");
        mvc.perform(get("/v1/songs").cookie(again)).andExpect(jsonPath("$.songs.length()").value(0));
    }
}
