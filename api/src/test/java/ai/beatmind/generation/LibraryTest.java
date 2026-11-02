package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** US-022 library (list, Unicode-aware search, rename, delete) and US-023 player audio. */
@DatabaseTest
@AutoConfigureMockMvc
class LibraryTest extends SongApiSupport {

    Cookie kabir;
    UUID hindiSong;
    UUID englishSong;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        kabir = adult("kabir@example.com");
        setPlan("kabir@example.com", "pro");
        UUID[] a = made(kabir, Map.of("kind", "song", "prompt", "धीमी रात, बाँसुरी", "title", "रात का सफ़र", "language", "hi"));
        UUID[] b = made(kabir, Map.of("kind", "song", "prompt", "Gym motivation with heavy drums", "language", "en"));
        workerFinishes(a[0], false);
        workerFinishes(b[0], false);
        hindiSong = a[1];
        englishSong = b[1];
    }

    @Test
    void listsNewestFirstWithTheAiLabel() throws Exception {
        mvc.perform(get("/v1/songs").cookie(kabir))
                .andExpect(jsonPath("$.songs.length()").value(2))
                .andExpect(jsonPath("$.songs[0].id").value(englishSong.toString()))
                .andExpect(jsonPath("$.songs[0].title").value("Gym motivation with heavy drums"))
                .andExpect(jsonPath("$.songs[0].label").value("AI-generated"))
                .andExpect(jsonPath("$.songs[0].status").value("succeeded"))
                .andExpect(jsonPath("$.songs[0].takes.length()").value(2))
                .andExpect(jsonPath("$.songs[0].rights").value("You own this song."))
                .andExpect(jsonPath("$.next").doesNotExist());
        mvc.perform(get("/v1/songs?limit=1").cookie(kabir))
                .andExpect(jsonPath("$.songs.length()").value(1))
                .andExpect(jsonPath("$.next").exists());
    }

    @Test
    void searchWorksInAnyScriptAndAcrossScripts() throws Exception {
        assertFinds("सफ़र", hindiSong);
        assertFinds("raat ka safar", hindiSong);
        assertFinds("Safar", hindiSong);
        assertFinds("बाँसुरी", hindiSong);
        assertFinds("GYM", englishSong);
        mvc.perform(get("/v1/songs").param("q", "tabla").cookie(kabir)).andExpect(jsonPath("$.songs.length()").value(0));
    }

    @Test
    void renameKeepsSearchAndLedgerInStep() throws Exception {
        mvc.perform(patch("/v1/songs/" + englishSong).cookie(kabir).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"सुबह की दौड़\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("सुबह की दौड़"))
                .andExpect(jsonPath("$.titleLatin").value("subaha ki daura"));
        assertFinds("subah", englishSong);
        mvc.perform(patch("/v1/songs/" + englishSong).cookie(kabir).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM provenance_event WHERE song_id = :id AND type = 'renamed'")
                .param("id", englishSong).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void deleteHidesTheSongAtOnce() throws Exception {
        mvc.perform(delete("/v1/songs/" + hindiSong).cookie(kabir)).andExpect(status().isNoContent());

        mvc.perform(get("/v1/songs").cookie(kabir)).andExpect(jsonPath("$.songs.length()").value(1));
        mvc.perform(get("/v1/songs/" + hindiSong).cookie(kabir)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/songs/" + hindiSong + "/takes/1/audio").cookie(kabir)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT type FROM provenance_event WHERE song_id = :id ORDER BY seq DESC LIMIT 1")
                .param("id", hindiSong).query(String.class).single()).isEqualTo("deleted");
    }

    @Test
    void anotherUsersSongIsInvisible() throws Exception {
        Cookie other = adult("other@example.com");
        mvc.perform(get("/v1/songs").cookie(other)).andExpect(jsonPath("$.songs.length()").value(0));
        mvc.perform(get("/v1/songs/" + hindiSong).cookie(other)).andExpect(status().isNotFound());
        mvc.perform(patch("/v1/songs/" + hindiSong).cookie(other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"mine now\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/v1/songs/" + hindiSong).cookie(other)).andExpect(status().isNotFound());
    }

    @Test
    void playerAudioIsTheLightMp3AndSupportsSeeking() throws Exception {
        mvc.perform(get("/v1/songs/" + hindiSong + "/takes/1/audio").cookie(kabir).header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", org.hamcrest.Matchers.startsWith("bytes 0-3/")))
                .andExpect(header().string("Content-Type", "audio/mpeg"));
        org.mockito.Mockito.verify(audio).open(org.mockito.ArgumentMatchers.endsWith("take-1-192.mp3"));
    }

    private void assertFinds(String query, UUID song) throws Exception {
        mvc.perform(get("/v1/songs").param("q", query).cookie(kabir))
                .andExpect(jsonPath("$.songs.length()").value(1))
                .andExpect(jsonPath("$.songs[0].id").value(song.toString()));
    }
}
