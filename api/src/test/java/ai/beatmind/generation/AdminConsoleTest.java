package ai.beatmind.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

/** US-031: role-gated console, MFA required, every action audited (REQ-SEC-06, TM-10). */
@DatabaseTest
@AutoConfigureMockMvc
class AdminConsoleTest extends SongApiSupport {

    @BeforeEach
    void setUp() {
        resetAll();
    }

    private String secret(Cookie c) throws Exception {
        String body = mvc.perform(post("/v1/me/mfa/setup").cookie(c)).andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/BeatMind%20AI%3Aadmin%40example.com?secret=")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("secret").asString();
    }

    private org.springframework.test.web.servlet.ResultActions send(String path, Cookie c, String code) throws Exception {
        return mvc.perform(post(path).cookie(c).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    @Test
    @Tag("TC-REQ-SEC-06-01")
    void adminNeedsASecondFactorEverySession() throws Exception {
        Cookie first = adult("admin@example.com");
        mvc.perform(get("/v1/me").cookie(first)).andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.adminVerified").value(false));
        mvc.perform(get("/v1/admin/users").cookie(first)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/me/mfa").cookie(first)).andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.enrolled").value(false));

        String secret = secret(first);
        send("/v1/me/mfa/confirm", first, "000000").andExpect(status().isBadRequest());
        String codes = send("/v1/me/mfa/confirm", first, SignInHelper.totp(secret, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/v1/admin/users").cookie(first)).andExpect(status().isOk());
        // Enrolment cannot be restarted by someone holding the session.
        mvc.perform(post("/v1/me/mfa/setup").cookie(first)).andExpect(status().isConflict());

        // A new session needs the factor again; the same code cannot be used twice.
        Cookie second = SignInHelper.signIn(mvc, "admin@example.com");
        mvc.perform(get("/v1/admin/users").cookie(second)).andExpect(status().isForbidden());
        send("/v1/me/mfa/verify", second, SignInHelper.totp(secret, Instant.now())).andExpect(status().isBadRequest());
        String recovery = json.readTree(codes).get("recoveryCodes").get(0).asString();
        send("/v1/me/mfa/verify", second, recovery).andExpect(status().isOk());
        mvc.perform(get("/v1/admin/users").cookie(second)).andExpect(status().isOk());

        // A recovery code works once.
        Cookie third = SignInHelper.signIn(mvc, "admin@example.com");
        send("/v1/me/mfa/verify", third, recovery).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/me/mfa").cookie(third)).andExpect(jsonPath("$.recoveryCodesLeft").value(9));
    }

    @Test
    void wrongCodesLockTheFactorForAWhile() throws Exception {
        Cookie c = adult("admin@example.com");
        String secret = secret(c);
        send("/v1/me/mfa/confirm", c, SignInHelper.totp(secret, Instant.now())).andExpect(status().isOk());
        Cookie next = SignInHelper.signIn(mvc, "admin@example.com");
        for (int i = 0; i < 5; i++) {
            send("/v1/me/mfa/verify", next, "12345" + i).andExpect(status().isBadRequest());
        }
        send("/v1/me/mfa/verify", next, SignInHelper.totp(secret, Instant.now().plusSeconds(30)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void secretIsStoredEncrypted() throws Exception {
        Cookie c = adult("admin@example.com");
        String secret = secret(c);
        String stored = jdbc.sql("SELECT totp_secret_enc FROM user_mfa").query(String.class).single();
        assertThat(stored).doesNotContain(secret);
    }

    @Test
    void usersCanBeFoundSuspendedAndResumedWithAnAuditTrail() throws Exception {
        Cookie neha = adult("neha@example.com");
        made(neha, "first song");
        Cookie admin = SignInHelper.signInAdmin(mvc, "admin@example.com");

        mvc.perform(get("/v1/admin/users?q=neha").cookie(admin))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("neha@example.com"))
                .andExpect(jsonPath("$[0].songs").value(1))
                .andExpect(jsonPath("$[0].suspended").value(false));

        mvc.perform(post("/v1/admin/users/suspend").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"neha@example.com\",\"suspended\":true,\"note\":\"spam report\"}"))
                .andExpect(status().isOk());
        vibe(neha, "second song").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("suspended"));
        mvc.perform(post("/v1/lyrics").cookie(neha).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"rain\",\"language\":\"en\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/me").cookie(neha)).andExpect(jsonPath("$.suspended").value(true));

        mvc.perform(post("/v1/admin/users/suspend").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"neha@example.com\",\"suspended\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/admin/audit").cookie(admin))
                .andExpect(jsonPath("$[0].action").value("user.resume"))
                .andExpect(jsonPath("$[1].action").value("user.suspend"))
                .andExpect(jsonPath("$[1].detail").value("spam report"))
                .andExpect(jsonPath("$[1].actor").value("admin@example.com"));
    }

    @Test
    void localeChoiceIsRemembered() throws Exception {
        Cookie neha = adult("neha@example.com");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/v1/me").cookie(neha)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locale\":\"hi\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locale").value("hi"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/v1/me").cookie(neha)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest());
    }
}
