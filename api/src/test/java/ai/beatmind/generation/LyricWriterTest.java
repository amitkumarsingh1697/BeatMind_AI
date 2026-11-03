package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

/**
 * US-018: the AI writes lyrics from a topic and mood; what it writes is screened before the
 * creator sees it (TM-06), and the ledger records which lyrics were AI-written (REQ-F-31).
 */
@DatabaseTest
@AutoConfigureMockMvc
class LyricWriterTest extends SongApiSupport {

    static final String LYRICS = "[Verse]\nबारिश की बूँदें, खिड़की पे गाना\n[Chorus]\nचाय की प्याली, तेरा बहाना\n";

    Cookie neha;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        neha = adult("neha@example.com");
    }

    private UUID ask(String topic, String mood) throws Exception {
        String body = mvc.perform(post("/v1/lyrics").cookie(neha).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("topic", topic, "mood", mood, "language", "hi"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("writing"))
                .andExpect(jsonPath("$.aiGenerated").value(true))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    @Test
    void lyricsJobIsQueuedAheadOfSongsAndReturnsSectionedLyrics() throws Exception {
        UUID draft = ask("Mumbai rain and chai", "cosy, nostalgic");
        assertThat(jdbc.sql("SELECT kind || ':' || priority || ':' || (payload->>'languageTag') FROM generation_job")
                .query(String.class).single()).isEqualTo("lyrics:5:hi");

        lyricWorkerWrites(draft, LYRICS);

        mvc.perform(get("/v1/lyrics/" + draft).cookie(neha))
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.lyrics").value(TextRules.nfc(LYRICS.strip())))
                .andExpect(jsonPath("$.languageTag").value("hi"));
        mvc.perform(get("/v1/lyrics/" + draft).cookie(adult("other@example.com"))).andExpect(status().isNotFound());
        // A lyric job is not a song: it does not appear in the creator's latest songs.
        mvc.perform(get("/v1/generations").cookie(neha)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unsafeOutputIsWithheldAndLogged() throws Exception {
        UUID draft = ask("a fight between neighbours", "angry");
        lyricWorkerWrites(draft, "[Verse]\nmain tujhe maar dalunga\n");
        mvc.perform(get("/v1/lyrics/" + draft).cookie(neha))
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.lyrics").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("safety check")));
        assertThat(jdbc.sql("SELECT kind || ':' || category FROM moderation_case").query(String.class).single())
                .isEqualTo("lyrics_block:threat");
    }

    @Test
    void unsafeTopicIsBlockedBeforeTheAiWrites() throws Exception {
        mvc.perform(post("/v1/lyrics").cookie(neha).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"a song like Taylor Swift\",\"language\":\"en\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("moderation_artist"));
        assertThat(jdbc.sql("SELECT count(*) FROM generation_job").query(Long.class).single()).isZero();
    }

    @Test
    void ledgerSaysTheAiWroteTheLyricsAndWhetherTheCreatorEditedThem() throws Exception {
        UUID draft = ask("Mumbai rain", "cosy");
        lyricWorkerWrites(draft, LYRICS);

        UUID[] asIs = made(neha, Map.of("kind", "song", "mode", "lyrics", "prompt", "lofi", "language", "hi",
                "lyrics", LYRICS, "lyricsDraftId", draft.toString()));
        assertThat(events(asIs[1])).isEqualTo("prompt_submitted,lyrics_generated,credits_debited");

        workerFinishes(asIs[0], false);
        UUID[] edited = made(neha, Map.of("kind", "song", "mode", "lyrics", "prompt", "lofi", "language", "hi",
                "lyrics", LYRICS + "[Bridge]\nमेरी अपनी लाइन\n", "lyricsDraftId", draft.toString()));
        assertThat(events(edited[1])).isEqualTo("prompt_submitted,lyrics_generated,lyrics_edited,credits_debited");
        assertThat(jdbc.sql("SELECT actor FROM provenance_event WHERE song_id = :s AND type = 'lyrics_generated'")
                .param("s", edited[1]).query(String.class).single()).isEqualTo("ai");

        // Someone else's draft id does not make lyrics count as AI-written.
        workerFinishes(edited[0], false);
        Cookie other = adult("other@example.com");
        UUID[] borrowed = made(other, Map.of("kind", "song", "mode", "lyrics", "prompt", "lofi", "language", "hi",
                "lyrics", LYRICS, "lyricsDraftId", draft.toString()));
        assertThat(events(borrowed[1])).isEqualTo("prompt_submitted,lyrics_written,credits_debited");
    }

    @Test
    @org.junit.jupiter.api.Tag("TC-REQ-F-07-01")
    void languagesThatHaveNotPassedG1AreExperimental() throws Exception {
        // No language has passed the G1 listening test yet, so all three are marked (REQ-F-07).
        mvc.perform(get("/v1/languages").cookie(neha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("hi"))
                .andExpect(jsonPath("$[0].experimental").value(true))
                .andExpect(jsonPath("$[1].languageTag").value("hi-Latn"))
                .andExpect(jsonPath("$[2].experimental").value(true));
        assertThat(new LanguagesController(java.util.List.of("hi", " en")).languages())
                .extracting(LanguagesController.Language::experimental).containsExactly(false, true, false);
    }
}
