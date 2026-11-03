package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** TDD 5.2: files of deleted songs are removed 30 days after deletion; the ledger stays. */
@DatabaseTest
@AutoConfigureMockMvc
class PurgeTest extends SongApiSupport {

    @Autowired
    SongPurge purge;

    Cookie neha;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        neha = adult("neha@example.com");
    }

    @Test
    void filesGoThirtyDaysAfterDeletionAndNotBefore() throws Exception {
        UUID[] ids = made(neha, "a song to delete");
        workerFinishes(ids[0], false);
        UUID[] kept = made(neha, "a song to keep");
        workerFinishes(kept[0], false);
        mvc.perform(delete("/v1/songs/" + ids[1]).cookie(neha)).andExpect(status().isNoContent());

        assertThat(purge.runOnce()).isZero();
        jdbc.sql("UPDATE song SET deleted_at = now() - interval '31 days' WHERE id = :id").param("id", ids[1]).update();
        assertThat(purge.runOnce()).isEqualTo(1);
        assertThat(purge.runOnce()).isZero();

        // Three files per take plus the raw take, for two takes.
        Mockito.verify(audio, Mockito.times(6)).delete(Mockito.argThat(k -> k.contains(ids[0].toString())));
        Mockito.verify(audio, Mockito.never()).delete(Mockito.argThat(k -> k.contains(kept[0].toString())));
        assertThat(jdbc.sql("SELECT prompt || '|' || title || '|' || (purged_at IS NOT NULL) FROM song WHERE id = :id")
                .param("id", ids[1]).query(String.class).single()).isEqualTo("|Deleted song|true");
        assertThat(jdbc.sql("SELECT count(*) FROM take WHERE song_id = :id AND assets <> '{}'::jsonb")
                .param("id", ids[1]).query(Long.class).single()).isZero();
        assertThat(events(ids[1])).endsWith("deleted,files_purged");
    }
}
