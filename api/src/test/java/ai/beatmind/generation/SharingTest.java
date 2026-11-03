package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** US-024: revocable share links and the public song page (FEAT-13, REQ-SEC-03, TM-04). */
@DatabaseTest
@AutoConfigureMockMvc
class SharingTest extends SongApiSupport {

    Cookie owner;
    UUID song;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        owner = adult("owner@example.com");
        UUID[] ids = made(owner, Map.of("kind", "song", "mode", "lyrics", "prompt", "acoustic ballad",
                "lyrics", "[Verse]\nरात का सफ़र\nचाँद के संग", "title", "रात का सफ़र", "language", "hi"));
        workerFinishes(ids[0], false);
        song = ids[1];
    }

    private ResultActions share(Cookie who, UUID id, String body) throws Exception {
        return mvc.perform(post("/v1/songs/" + id + "/shares").cookie(who).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    @Tag("TC-REQ-SEC-03-02")
    void publicLinkPlaysForAnyoneUntilRevoked() throws Exception {
        String body = share(owner, song, "{\"visibility\":\"public\",\"takeIndex\":2,\"locale\":\"hi\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.matchesPattern("/hi/s/[A-Za-z0-9_-]{32}")))
                .andReturn().getResponse().getContentAsString();
        String url = json.readTree(body).get("url").asString();
        UUID shareId = UUID.fromString(json.readTree(body).get("id").asString());
        String token = token(url);
        // Only a hash of the token is stored.
        assertThat(jdbc.sql("SELECT count(*) FROM share_link WHERE token_hash = :t").param("t", token)
                .query(Long.class).single()).isZero();

        // Signed out: the page data and the audio.
        String page = mvc.perform(get("/v1/shared/" + token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("रात का सफ़र"))
                .andExpect(jsonPath("$.label").value("AI-generated"))
                .andExpect(jsonPath("$.aiGenerated").value(true))
                .andExpect(jsonPath("$.visibility").value("public"))
                .andExpect(jsonPath("$.audioUrl").value("/v1/shared/" + token + "/audio"))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("owner@example.com").doesNotContain("prompt");
        mvc.perform(get("/v1/shared/" + token + "/audio")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mpeg"));
        // The listed link has no token, only a hint, and counts views.
        mvc.perform(get("/v1/songs/" + song + "/shares").cookie(owner))
                .andExpect(jsonPath("$[0].url").doesNotExist())
                .andExpect(jsonPath("$[0].hint").value(token.substring(token.length() - 4)))
                .andExpect(jsonPath("$[0].views").value(1))
                .andExpect(jsonPath("$[0].takeIndex").value(2));

        mvc.perform(delete("/v1/songs/" + song + "/shares/" + shareId).cookie(owner)).andExpect(status().isNoContent());
        mvc.perform(get("/v1/shared/" + token)).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("revoked"));
        mvc.perform(get("/v1/shared/" + token + "/audio")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/shared/" + "x".repeat(32))).andExpect(status().isNotFound());
        assertThat(events(song)).contains("shared", "share_revoked");
    }

    @Test
    void deletingTheSongStopsItsLinks() throws Exception {
        String url = json.readTree(share(owner, song, "{\"visibility\":\"private\"}").andReturn().getResponse()
                .getContentAsString()).get("url").asString();
        mvc.perform(delete("/v1/songs/" + song).cookie(owner)).andExpect(status().isNoContent());
        mvc.perform(get("/v1/shared/" + token(url))).andExpect(status().isGone());
    }

    @Test
    void onlyTheOwnerSharesAndOnlyFinishedTakes() throws Exception {
        Cookie other = adult("other@example.com");
        share(other, song, "{\"visibility\":\"public\"}").andExpect(status().isNotFound());
        mvc.perform(get("/v1/songs/" + song + "/shares").cookie(other)).andExpect(status().isNotFound());
        share(owner, song, "{\"visibility\":\"public\",\"takeIndex\":3}").andExpect(status().isConflict());
        share(owner, song, "{\"visibility\":\"everyone\"}").andExpect(status().isBadRequest());
        UUID[] pending = made(owner, "still cooking");
        share(owner, pending[1], "{\"visibility\":\"public\"}").andExpect(status().isConflict());
    }

    @Test
    @Tag("TC-REQ-F-50-03")
    void titleAndLyricsAreScreenedAgainBeforeSharing() throws Exception {
        // A title that passed when the song was made, but a rule added since blocks it.
        jdbc.sql("UPDATE song SET title = 'Arijit Singh tribute' WHERE id = :id").param("id", song).update();
        share(owner, song, "{\"visibility\":\"public\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.caseId").isNotEmpty());
        assertThat(jdbc.sql("SELECT count(*) FROM share_link").query(Long.class).single()).isZero();
    }
}
