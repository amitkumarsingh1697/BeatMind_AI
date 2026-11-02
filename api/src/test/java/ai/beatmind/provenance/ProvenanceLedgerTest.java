package ai.beatmind.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** US-019: the hash-chained provenance ledger (REQ-F-31, REQ-SEC-07). */
@DatabaseTest
@AutoConfigureMockMvc
class ProvenanceLedgerTest {

    @Autowired
    ProvenanceLedger ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MockMvc mvc;

    UUID song;

    @BeforeEach
    void setUp() {
        SignInHelper.reset(jdbc);
        song = UUID.randomUUID();
        ledger.append(song, "prompt_submitted", Actor.HUMAN, Map.of("promptSha256", "ab12", "settings", Map.of("b", 2, "a", 1)));
        ledger.append(song, "take_generated", Actor.AI, Map.of("takeIndex", 1, "model", "ace-step-1.5"));
        ledger.append(song, "exported", Actor.HUMAN, Map.of("takeIndex", 1, "format", "mp3_192"));
    }

    @Test
    @Tag("TC-REQ-F-31-01")
    void eventsChainAndVerify() {
        List<ProvenanceLedger.Event> events = ledger.events(song);
        assertThat(events).extracting(ProvenanceLedger.Event::type)
                .containsExactly("prompt_submitted", "take_generated", "exported");
        assertThat(events.get(0).prevHash()).isEqualTo(ProvenanceLedger.GENESIS);
        assertThat(events.get(1).prevHash()).isEqualTo(events.get(0).hash());
        assertThat(events.get(2).prevHash()).isEqualTo(events.get(1).hash());
        assertThat(ledger.head(song)).isEqualTo(events.get(2).hash());
        assertThat(ledger.verify(song).valid()).isTrue();

        // Canonical form: keys sorted at every level, so the same event always hashes the same.
        String body = jdbc.sql("SELECT body FROM provenance_event WHERE song_id = :s AND seq = 1").param("s", song)
                .query(String.class).single();
        assertThat(body).startsWith("{\"actor\":\"human\",\"createdAt\":").contains("\"settings\":{\"a\":1,\"b\":2}");
    }

    @Test
    @Tag("TC-REQ-F-31-02")
    void tamperingBreaksTheChain() {
        tamper("UPDATE provenance_event SET body = replace(body, 'ace-step-1.5', 'human-made') WHERE song_id = :s AND seq = 2");

        ProvenanceLedger.Verification v = ledger.verify(song);
        assertThat(v.valid()).isFalse();
        assertThat(v.brokenAtSeq()).isEqualTo(2);
        assertThat(v.reason()).isEqualTo("hash does not match the event");
    }

    @Test
    @Tag("TC-REQ-F-31-02")
    void rewritingAHashOrDeletingAnEventIsAlsoCaught() {
        tamper("UPDATE provenance_event SET actor = 'human' WHERE song_id = :s AND seq = 2");
        assertThat(ledger.verify(song).reason()).isEqualTo("stored columns differ from the hashed event");

        UUID other = UUID.randomUUID();
        ledger.append(other, "a", Actor.SYSTEM, Map.of());
        ledger.append(other, "b", Actor.SYSTEM, Map.of());
        ledger.append(other, "c", Actor.SYSTEM, Map.of());
        song = other;
        tamper("DELETE FROM provenance_event WHERE song_id = :s AND seq = 2");
        assertThat(ledger.verify(other).reason()).isEqualTo("sequence gap");

        ProvenanceLedger.Summary all = ledger.verifyAll();
        assertThat(all.valid()).isFalse();
        assertThat(all.broken()).hasSize(2);
    }

    @Test
    void theTableIsAppendOnly() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE provenance_event SET type = 'x'").update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM provenance_event").update())
                .rootCause().hasMessageContaining("append-only");
        jdbc.sql("INSERT INTO credit_ledger (user_id, delta, reason, balance_after) VALUES (gen_random_uuid(), -2, 'generation', -2)")
                .update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE credit_ledger SET delta = 2").update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM credit_ledger").update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void concurrentAppendsKeepOneChain() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 20)
                    .mapToObj(i -> pool.submit(() -> ledger.append(song, "exported", Actor.HUMAN, Map.of("n", i))))
                    .toList();
            for (var f : futures) {
                f.get();
            }
        }
        ProvenanceLedger.Verification v = ledger.verify(song);
        assertThat(v.valid()).isTrue();
        assertThat(v.events()).isEqualTo(23);
    }

    @Test
    void adminCanVerifyEveryChain() throws Exception {
        Cookie admin = SignInHelper.signIn(mvc, "admin@example.com");
        mvc.perform(get("/v1/admin/ledger/verify").cookie(admin))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.songs").value(1))
                .andExpect(jsonPath("$.events").value(3));
        Cookie user = SignInHelper.signIn(mvc, "user@example.com");
        mvc.perform(get("/v1/admin/ledger/verify").cookie(user)).andExpect(status().isForbidden());
    }

    /** Changes rows the way an insider with database superuser rights could, bypassing the trigger. */
    private void tamper(String sql) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql(sql).param("s", song).update();
        });
    }
}
