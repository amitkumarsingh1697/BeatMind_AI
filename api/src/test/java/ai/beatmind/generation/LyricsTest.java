package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** US-017: songs from the creator's own lyrics in Hindi, Hinglish or English (REQ-F-02, REQ-F-08). */
@DatabaseTest
@AutoConfigureMockMvc
class LyricsTest extends SongApiSupport {

    Cookie meera;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        meera = adult("meera@example.com");
        // Pro allows four songs in progress at once, so one test can send several.
        setPlan("meera@example.com", "pro");
    }

    private Map<String, Object> lyrics(String text, String language) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "song");
        body.put("mode", "lyrics");
        body.put("lyrics", text);
        body.put("prompt", "slow acoustic ballad");
        body.put("language", language);
        body.put("vocalType", "female");
        return body;
    }

    @Test
    @Tag("TC-REQ-F-02-01")
    void devanagariLyricsCountByCharacterUpTo3000() throws Exception {
        // "क्षि" is four code points and one user-perceived character, as browsers count it.
        String conjunct = "क्षि";
        assertThat(TextRules.graphemes(conjunct)).isEqualTo(1);

        request(meera, lyrics(conjunct.repeat(3000), "hi")).andExpect(status().isAccepted());
        request(meera, lyrics(conjunct.repeat(3001), "hi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_lyrics"))
                .andExpect(jsonPath("$.detail").value("Keep the lyrics to 3,000 characters."));
    }

    @Test
    @Tag("TC-REQ-F-02-02")
    void hinglishAndEnglishLyricsAreAcceptedWithVocalType() throws Exception {
        UUID[] hinglish = made(meera, lyrics("[Verse]\nRaat ka safar, tere bina\nChalte rahe hum", "hinglish"));
        UUID[] english = made(meera, lyrics("Late night drive\nCity lights", "en"));

        var payload = jdbc.sql("SELECT payload->>'mode', payload->>'lyrics', payload->>'vocalType', payload->>'languageTag' FROM generation_job WHERE id = :id")
                .param("id", hinglish[0])
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4))
                .single();
        assertThat(payload).isEqualTo("lyrics|[Verse]\nRaat ka safar, tere bina\nChalte rahe hum|female|hi-Latn");
        // The title defaults to the first sung line, not the [Verse] tag.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/songs/" + hinglish[1]).cookie(meera))
                .andExpect(jsonPath("$.title").value("Raat ka safar, tere bina"))
                .andExpect(jsonPath("$.mode").value("lyrics"))
                .andExpect(jsonPath("$.languageTag").value("hi-Latn"));
        assertThat(english[1]).isNotNull();

        Map<String, Object> sungInstrumental = lyrics("words", "en");
        sungInstrumental.put("instrumental", true);
        request(meera, sungInstrumental).andExpect(status().isBadRequest());
        Map<String, Object> empty = lyrics("   ", "en");
        request(meera, empty).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_lyrics"));
    }

    @Test
    @Tag("TC-REQ-F-08-01")
    void textIsStoredNfcWithLanguageTagAndLatinTitle() throws Exception {
        Map<String, Object> body = lyrics("café song", "en");
        body.put("title", "रात का सफ़र");
        UUID[] ids = made(meera, body);

        var row = jdbc.sql("SELECT lyrics, title, title_latin, language_tag FROM song WHERE id = :id")
                .param("id", ids[1])
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                .single();
        assertThat(row[0]).isEqualTo("café song");
        assertThat(row[1]).isEqualTo("रात का सफ़र");
        assertThat(row[2]).isEqualTo("rata ka safara");
        assertThat(row[3]).isEqualTo("en");
        // Human inputs are in the ledger as hashes, not as text.
        String events = jdbc.sql("SELECT string_agg(type || ':' || actor, ',' ORDER BY seq) FROM provenance_event WHERE song_id = :id")
                .param("id", ids[1]).query(String.class).single();
        assertThat(events).isEqualTo("prompt_submitted:human,lyrics_written:human,credits_debited:system");
        String body1 = jdbc.sql("SELECT body FROM provenance_event WHERE song_id = :id AND seq = 2")
                .param("id", ids[1]).query(String.class).single();
        assertThat(body1).contains("lyricsSha256").doesNotContain("song\"");
    }

    @Test
    void graphemeCountsMatchTheBrowser() {
        // Counts from Intl.Segmenter in Chrome and Node (ICU), which the page's counter uses.
        assertThat(TextRules.graphemes("दिल्ली")).isEqualTo(2);
        assertThat(TextRules.graphemes("रात का सफ़र")).isEqualTo(8);
        assertThat(TextRules.graphemes("👩🏽‍🎤")).isEqualTo(1);
        assertThat(TextRules.truncate("क्षिक्षि", 1)).isEqualTo("क्षि");
    }
}
