package ai.beatmind.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** US-009 email one-time code sign-in, with the TM-01 and TM-02 controls (REQ-SEC-01). */
@DatabaseTest
@AutoConfigureMockMvc
@Tag("TC-REQ-SEC-01-01")
class EmailSignInTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IdentityCleanup cleanup;

    @BeforeEach
    void clean() {
        SignInHelper.reset(jdbc);
    }

    @Test
    void rightCodeStartsAnHttpOnlySession() throws Exception {
        String code = SignInHelper.devCode(mvc, "Asha@Example.com");

        var result = verify("asha@example.com", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.needsProfile").value(true))
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("bm_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
        mvc.perform(get("/v1/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.com"));
    }

    @Test
    void storesOnlyHashesOfCodesAndSessionTokens() throws Exception {
        String code = SignInHelper.devCode(mvc, "asha@example.com");
        Cookie cookie = verify("asha@example.com", code).andReturn().getResponse().getCookie("bm_session");

        String codeHash = jdbc.sql("SELECT code_hash FROM email_code").query(String.class).single();
        String tokenHash = jdbc.sql("SELECT token_hash FROM user_session").query(String.class).single();
        assertThat(codeHash).doesNotContain(code).hasSize(64);
        assertThat(tokenHash).isNotEqualTo(cookie.getValue()).hasSize(64);
    }

    @Test
    void wrongCodeIsRefused() throws Exception {
        String code = SignInHelper.devCode(mvc, "asha@example.com");
        String wrong = code.equals("000000") ? "111111" : "000000";

        verify("asha@example.com", wrong).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM user_session").query(Long.class).single()).isZero();
    }

    @Test
    void codeStopsWorkingAfterFiveWrongAttempts() throws Exception {
        String code = SignInHelper.devCode(mvc, "asha@example.com");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < EmailCodeService.MAX_ATTEMPTS; i++) {
            verify("asha@example.com", wrong).andExpect(status().isUnauthorized());
        }

        verify("asha@example.com", code).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredCodeIsRefused() throws Exception {
        String code = SignInHelper.devCode(mvc, "asha@example.com");
        jdbc.sql("UPDATE email_code SET expires_at = now() - interval '1 second'").update();

        verify("asha@example.com", code).andExpect(status().isUnauthorized());
    }

    @Test
    void codeWorksOnlyOnce() throws Exception {
        String code = SignInHelper.devCode(mvc, "asha@example.com");
        verify("asha@example.com", code).andExpect(status().isOk());

        verify("asha@example.com", code).andExpect(status().isUnauthorized());
    }

    @Test
    void limitsCodesPerEmail() throws Exception {
        for (int i = 0; i < EmailCodeService.MAX_CODES_PER_EMAIL_PER_HOUR; i++) {
            start("asha@example.com").andExpect(status().isAccepted());
        }

        start("asha@example.com").andExpect(status().isTooManyRequests());
    }

    @Test
    void limitsCodesPerIpAddress() throws Exception {
        for (int i = 0; i < EmailCodeService.MAX_CODES_PER_IP_PER_HOUR; i++) {
            start("user" + i + "@example.com").andExpect(status().isAccepted());
        }

        start("one-more@example.com").andExpect(status().isTooManyRequests());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Cookie cookie = SignInHelper.signIn(mvc, "asha@example.com");

        mvc.perform(post("/v1/auth/logout").cookie(cookie)).andExpect(status().isNoContent());

        mvc.perform(get("/v1/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void idleSessionEnds() throws Exception {
        Cookie cookie = SignInHelper.signIn(mvc, "asha@example.com");
        jdbc.sql("UPDATE user_session SET last_seen_at = now() - interval '4 days'").update();

        mvc.perform(get("/v1/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSessionEnds() throws Exception {
        Cookie cookie = SignInHelper.signIn(mvc, "asha@example.com");
        jdbc.sql("UPDATE user_session SET expires_at = now() - interval '1 second'").update();

        mvc.perform(get("/v1/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedCookieIsIgnored() throws Exception {
        mvc.perform(get("/v1/me").cookie(new Cookie("bm_session", "made-up"))).andExpect(status().isUnauthorized());
    }

    @Test
    void sameEmailSignsInToTheSameAccount() throws Exception {
        SignInHelper.signIn(mvc, "asha@example.com");
        SignInHelper.signIn(mvc, "ASHA@example.com");

        assertThat(jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void cleanupDeletesOldCodesAndExpiredSessions() throws Exception {
        Cookie live = SignInHelper.signIn(mvc, "asha@example.com");
        SignInHelper.signIn(mvc, "ravi@example.com");
        jdbc.sql("UPDATE email_code SET created_at = now() - interval '2 days'").update();
        jdbc.sql("UPDATE user_session SET expires_at = now() - interval '1 second' WHERE user_id = "
                + "(SELECT id FROM app_user WHERE email = 'ravi@example.com')").update();

        cleanup.purge();

        assertThat(jdbc.sql("SELECT count(*) FROM email_code").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM user_session").query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/v1/me").cookie(live)).andExpect(status().isOk());
    }

    private ResultActions start(String email) throws Exception {
        return mvc.perform(post("/v1/auth/email/start").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
    }

    private ResultActions verify(String email, String code) throws Exception {
        return mvc.perform(post("/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)));
    }
}
