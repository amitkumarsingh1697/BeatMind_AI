package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** Vibe mode with ACE-Step: the lyrics the model wrote are checked, stored and shown on the song. */
@DatabaseTest
@AutoConfigureMockMvc
class VibeLyricsTest extends SongApiSupport {

    Cookie neha;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        neha = adult("neha@example.com");
    }

    private void finishWithLyrics(UUID jobId, String lyrics) {
        String take = """
                {"key":"takes/%1$s/take-%2$d.mp3","format":"mp3","runner":"acestep","watermarkId":%3$d,"c2pa":true,
                 "assets":{"mp3_192":{"key":"takes/%1$s/take-%2$d-192.mp3","contentType":"audio/mpeg","sha256":"a%2$d","bytes":10}}}
                """;
        String result = json.writeValueAsString(Map.of("lyrics", lyrics));
        result = "{\"takes\":[" + take.formatted(jobId, 1, 2001) + "," + take.formatted(jobId, 2, 2002) + "],"
                + result.substring(1);
        jdbc.sql("UPDATE generation_job SET status = 'succeeded', finished_at = now(), result = CAST(:r AS jsonb) WHERE id = :id")
                .param("r", result).param("id", jobId).update();
        finalizer.runOnce();
    }

    @Test
    @Tag("TC-REQ-F-31-01")
    void lyricsTheModelWroteShowOnTheSongAndInTheLedger() throws Exception {
        UUID[] ids = made(neha, "romantic wedding duet, bhojpuri vibe");
        finishWithLyrics(ids[0], "[verse]\nतेरी आँखों में सपने हैं\n");

        mvc.perform(get("/v1/songs/" + ids[1]).cookie(neha))
                .andExpect(jsonPath("$.lyrics").value("[verse]\nतेरी आँखों में सपने हैं"))
                .andExpect(jsonPath("$.takes[0].status").value("ready"))
                .andExpect(jsonPath("$.takes[1].status").value("ready"));
        assertThat(events(ids[1])).contains("lyrics_generated").doesNotContain("lyrics_written");
        assertThat(jdbc.sql("""
                SELECT actor FROM provenance_event WHERE song_id = :s AND type = 'lyrics_generated'
                """).param("s", ids[1]).query(String.class).single()).isEqualTo("ai");
    }

    @Test
    @Tag("TC-REQ-F-31-01")
    void blockedWordsHoldBackTheTakesThatSingThem() throws Exception {
        UUID[] ids = made(neha, "angry drill");
        finishWithLyrics(ids[0], "[Verse]\nmain tujhe maar dalunga\n");

        mvc.perform(get("/v1/songs/" + ids[1]).cookie(neha))
                .andExpect(jsonPath("$.lyrics").doesNotExist())
                .andExpect(jsonPath("$.takes[0].status").value("withheld"))
                .andExpect(jsonPath("$.takes[1].status").value("withheld"));
        assertThat(jdbc.sql("SELECT string_agg(kind, ',') FROM moderation_case WHERE song_id IS NULL")
                .query(String.class).single()).isEqualTo("lyrics_block");
    }
}
