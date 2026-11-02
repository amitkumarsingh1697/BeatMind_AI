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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** US-026 downloads: signed links that last 15 minutes, formats by plan (REQ-F-20, REQ-F-23). */
@DatabaseTest
@AutoConfigureMockMvc
class DownloadsTest extends SongApiSupport {

    @Autowired
    DownloadTokens tokens;

    Cookie free;
    UUID song;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        free = adult("free@example.com");
        UUID[] ids = made(free, Map.of("kind", "song", "prompt", "x", "title", "रात का सफ़र", "language", "hi"));
        workerFinishes(ids[0], false);
        song = ids[1];
    }

    @AfterEach
    void realClock() {
        tokens.setClock(Clock.systemUTC());
    }

    private ResultActions link(Cookie cookie, String format) throws Exception {
        return mvc.perform(post("/v1/songs/" + song + "/takes/1/downloads").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"" + format + "\"}"));
    }

    private String url(Cookie cookie, String format) throws Exception {
        String body = link(cookie, format).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("url").asString();
    }

    @Test
    @Tag("TC-REQ-F-20-01")
    void freePlanGetsMp3At192WithTheLabelAndNoWav() throws Exception {
        link(free, "mp3")
                .andExpect(jsonPath("$.quality").value("MP3, 192 kbps, personal listening"))
                .andExpect(jsonPath("$.label").value("AI-generated"))
                .andExpect(jsonPath("$.rights").value("Personal listening only. BeatMind owns this song."))
                .andExpect(jsonPath("$.fileName").value("raat-ka-safar-take-1.mp3"));
        link(free, "wav").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan"));

        // The link works without a session (it is the permission) and serves the 192 kbps file.
        mvc.perform(get(url(free, "mp3")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("raat-ka-safar-take-1.mp3")))
                .andExpect(header().string("Cache-Control", "no-store"));
        org.mockito.Mockito.verify(audio, org.mockito.Mockito.atLeastOnce())
                .open(org.mockito.ArgumentMatchers.endsWith("take-1-192.mp3"));
    }

    @Test
    @Tag("TC-REQ-F-23-01")
    void paidPlanGetsWavAndMp3At320() throws Exception {
        setPlan("free@example.com", "creator");

        link(free, "wav").andExpect(jsonPath("$.quality").value("WAV, 48 kHz, 24-bit"));
        link(free, "mp3").andExpect(jsonPath("$.quality").value("MP3, 320 kbps"));
        mvc.perform(get(url(free, "wav"))).andExpect(status().isOk()).andExpect(header().string("Content-Type", "audio/wav"));
        // Rights stay as they were when the song was made (REQ-F-25): this one was made on Free.
        mvc.perform(get("/v1/songs/" + song).cookie(free))
                .andExpect(jsonPath("$.rightsStatus").value("bm_owned"))
                .andExpect(jsonPath("$.downloadFormats.length()").value(2));
    }

    @Test
    void linksExpireAfter15MinutesAndCannotBeForged() throws Exception {
        String url = url(free, "mp3");
        tokens.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(14)));
        mvc.perform(get(url)).andExpect(status().isOk());
        tokens.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(16)));
        mvc.perform(get(url)).andExpect(status().isGone());
        tokens.setClock(Clock.fixed(Instant.now(), ZoneOffset.UTC));

        String token = url.substring(url.lastIndexOf('/') + 1);
        char first = token.charAt(0);
        String forged = (first == 'A' ? 'B' : 'A') + token.substring(1);
        mvc.perform(get("/v1/downloads/" + forged)).andExpect(status().isGone());
        mvc.perform(get("/v1/downloads/nonsense")).andExpect(status().isGone());
    }

    @Test
    void everyExportIsInTheLedgerAndDeletedSongsStopDownloading() throws Exception {
        String url = url(free, "mp3");
        assertThat(jdbc.sql("SELECT count(*) FROM provenance_event WHERE song_id = :id AND type = 'exported'")
                .param("id", song).query(Long.class).single()).isEqualTo(1);

        mvc.perform(delete("/v1/songs/" + song).cookie(free)).andExpect(status().isNoContent());
        mvc.perform(get(url)).andExpect(status().isNotFound());
    }

    @Test
    void withheldTakesCannotBeDownloaded() throws Exception {
        UUID[] ids = made(free, "second");
        workerFinishes(ids[0], true);
        mvc.perform(post("/v1/songs/" + ids[1] + "/takes/2/downloads").cookie(free)
                .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"mp3\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/songs/" + ids[1]).cookie(free))
                .andExpect(jsonPath("$.takes[1].status").value("withheld"))
                .andExpect(jsonPath("$.takes[1].audioUrl").doesNotExist());
    }
}
