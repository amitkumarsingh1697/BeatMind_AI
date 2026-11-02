package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** US-027: failed, timed-out and withheld takes are refunded with a ledger entry (REQ-F-56). */
@DatabaseTest
@AutoConfigureMockMvc
class RefundsTest extends SongApiSupport {

    Cookie neha;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        neha = adult("neha@example.com");
    }

    private String credits(UUID job) {
        return jdbc.sql("SELECT string_agg(reason || ':' || delta, ',' ORDER BY id) FROM credit_ledger WHERE job_id = :job")
                .param("job", job).query(String.class).single();
    }

    @Test
    @Tag("TC-REQ-F-56-01")
    void failedJobRefundsBothTakes() throws Exception {
        UUID[] ids = made(neha, "will fail");
        mvc.perform(get("/v1/credits").cookie(neha)).andExpect(jsonPath("$.allowance.used").value(2));

        workerFails(ids[0]);

        assertThat(credits(ids[0])).isEqualTo("generation:-2,refund_failed:2");
        mvc.perform(get("/v1/credits").cookie(neha))
                .andExpect(jsonPath("$.allowance.used").value(0))
                .andExpect(jsonPath("$.allowance.remaining").value(20))
                .andExpect(jsonPath("$.entries[0].reason").value("refund_failed"))
                .andExpect(jsonPath("$.entries[0].delta").value(2))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(0));
        mvc.perform(get("/v1/generations/" + ids[0]).cookie(neha))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.refundedCredits").value(2))
                .andExpect(jsonPath("$.error").value("We could not make this song. Your credits have been refunded."));
        assertThat(ledgerTypes(ids[1])).endsWith("generation_failed,credits_refunded");
    }

    @Test
    @Tag("TC-REQ-F-56-01")
    void timedOutJobIsRefundedLikeAFailure() throws Exception {
        UUID[] ids = made(neha, "slow");
        // The worker's lease ran out on the last attempt (queue.requeue_stale moves it to dead).
        jdbc.sql("UPDATE generation_job SET status = 'dead', error = 'worker lease expired', finished_at = now() WHERE id = :id")
                .param("id", ids[0]).update();
        finalizer.runOnce();

        assertThat(credits(ids[0])).isEqualTo("generation:-2,refund_failed:2");
    }

    @Test
    @Tag("TC-REQ-F-56-01")
    void withheldTakeRefundsOneCredit() throws Exception {
        UUID[] ids = made(neha, "one withheld");
        workerFinishes(ids[0], true);

        assertThat(credits(ids[0])).isEqualTo("generation:-2,refund_withheld:1");
        mvc.perform(get("/v1/songs/" + ids[1]).cookie(neha))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.takes[0].status").value("ready"))
                .andExpect(jsonPath("$.takes[1].status").value("withheld"));
        assertThat(ledgerTypes(ids[1])).endsWith("take_generated,take_withheld,credits_refunded");
    }

    @Test
    void successIsNotRefundedAndRetriesNeverRefundTwice() throws Exception {
        UUID[] ok = made(neha, "fine");
        workerFinishes(ok[0], false);
        assertThat(credits(ok[0])).isEqualTo("generation:-2");
        assertThat(finalizer.runOnce()).isZero();

        UUID[] bad = made(neha, "fails then works");
        workerFails(bad[0]);
        Cookie admin = SignInHelper.signIn(mvc, "admin@example.com");
        mvc.perform(post("/v1/admin/jobs/" + bad[0] + "/retry").cookie(admin)).andExpect(status().isAccepted());
        workerFails(bad[0]);
        assertThat(credits(bad[0])).isEqualTo("generation:-2,refund_failed:2");
    }

    private String ledgerTypes(UUID song) {
        return jdbc.sql("SELECT string_agg(type, ',' ORDER BY seq) FROM provenance_event WHERE song_id = :id")
                .param("id", song).query(String.class).single();
    }
}
