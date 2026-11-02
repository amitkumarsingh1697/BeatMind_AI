package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import ai.beatmind.botcheck.TurnstileVerifier;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** US-030: Turnstile, daily caps set by an admin, concurrency and velocity rules (REQ-F-55, REQ-SEC-09). */
@DatabaseTest
@AutoConfigureMockMvc
class CapsTest extends SongApiSupport {

    @MockitoBean
    TurnstileVerifier turnstile;

    Cookie tara;
    Cookie admin;

    @BeforeEach
    void setUp() throws Exception {
        resetAll();
        given(turnstile.verify(any(), any())).willReturn(true);
        tara = adult("tara@example.com");
        admin = SignInHelper.signIn(mvc, "admin@example.com");
    }

    private void setDailyCap(Cookie who, int credits, int expectStatus) throws Exception {
        mvc.perform(post("/v1/admin/caps").cookie(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"freeDailyCredits\":" + credits + "}")).andExpect(status().is(expectStatus));
    }

    @Test
    @Tag("TC-REQ-F-55-01")
    void adminSetsTheFreeDailyCapAndRefundsDoNotCount() throws Exception {
        setDailyCap(tara, 4, 403);
        setDailyCap(admin, 4, 200);
        assertThat(jdbc.sql("SELECT action || '|' || detail FROM admin_audit").query(String.class).single())
                .isEqualTo("caps.free_daily_credits|20 -> 4");

        workerFinishes(made(tara, "one")[0], false);
        UUID failed = made(tara, "two")[0];
        workerFails(failed);
        // The failed song was refunded, so there is room for one more.
        workerFinishes(made(tara, "three")[0], false);
        vibe(tara, "four").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("allowance"));
        mvc.perform(get("/v1/credits").cookie(tara))
                .andExpect(jsonPath("$.allowance.window").value("day"))
                .andExpect(jsonPath("$.allowance.limit").value(4))
                .andExpect(jsonPath("$.allowance.remaining").value(0));
    }

    @Test
    @Tag("TC-REQ-F-55-01")
    void adminCanGiveOneAccountItsOwnCapAndPlan() throws Exception {
        mvc.perform(post("/v1/admin/users/plan").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"tara@example.com\",\"dailyCapOverride\":2}")).andExpect(status().isOk());
        workerFinishes(made(tara, "one")[0], false);
        vibe(tara, "two").andExpect(status().isTooManyRequests());

        mvc.perform(post("/v1/admin/users/plan").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"tara@example.com\",\"plan\":\"creator\"}")).andExpect(status().isOk());
        mvc.perform(get("/v1/credits").cookie(tara))
                .andExpect(jsonPath("$.allowance.window").value("month"))
                .andExpect(jsonPath("$.allowance.concurrency").value(2));
        vibe(tara, "two").andExpect(status().isAccepted());
        mvc.perform(post("/v1/admin/users/plan").cookie(tara).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"tara@example.com\",\"plan\":\"pro\"}")).andExpect(status().isForbidden());
    }

    @Test
    @Tag("TC-REQ-SEC-09-01")
    void velocityRulesPerAccountAndPerNetwork() throws Exception {
        for (int i = 0; i < 6; i++) {
            workerFinishes(made(tara, "song " + i)[0], false);
        }
        vibe(tara, "seventh").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("velocity"));

        // Accounts sharing one address: the per-network rule (60 an hour by default) still applies.
        jdbc.sql("""
                INSERT INTO song (id, owner_id, mode, title, title_latin, language_tag, prompt, duration_seconds,
                                  plan_at_creation, rights_status, search_text, search_key, client_ip_hash)
                SELECT gen_random_uuid(), u.id, 'vibe', 't', 't', 'en', 'p', 60, 'free', 'bm_owned', 't', 't', s.client_ip_hash
                  FROM generate_series(1, 60), app_user u,
                       (SELECT client_ip_hash FROM song LIMIT 1) s
                 WHERE u.email = 'admin@example.com'
                """).update();
        Cookie ravi = adult("ravi@example.com");
        vibe(ravi, "new account, same network").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("velocity"));
    }

    @Test
    @Tag("TC-REQ-SEC-09-01")
    void botCheckIsEnforcedOnSongRequests() throws Exception {
        given(turnstile.verify(any(), any())).willReturn(false);
        request(tara, Map.of("kind", "song", "prompt", "x", "turnstileToken", "bad"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("bot_check"));
        assertThat(jdbc.sql("SELECT count(*) FROM song").query(Long.class).single()).isZero();
    }
}
