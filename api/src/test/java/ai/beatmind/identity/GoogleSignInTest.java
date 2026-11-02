package ai.beatmind.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.beatmind.DatabaseTest;
import ai.beatmind.SignInHelper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Google sign-in (US-009, INT-11) with Google's token endpoint and keys replaced by mocks. */
@DatabaseTest
@AutoConfigureMockMvc
class GoogleSignInTest {

    static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    GoogleSignIn.GoogleTokenClient tokens;

    @MockitoBean
    GoogleIdTokenDecoder decoder;

    @BeforeEach
    void clean() {
        SignInHelper.reset(jdbc);
        given(tokens.exchange(anyString(), anyString())).willReturn("id-token");
    }

    @Test
    void startRedirectsToGoogleWithPkceAndState() throws Exception {
        MvcResult start = mvc.perform(get("/v1/auth/google/start")).andExpect(status().isFound()).andReturn();

        String location = start.getResponse().getHeader("Location");
        assertThat(location).startsWith(GoogleSignIn.AUTHORIZE_URL)
                .contains("code_challenge_method=S256", "client_id=" + CLIENT_ID, "nonce=", "state=");
        assertThat(start.getResponse().getCookie(AuthController.OAUTH_COOKIE).isHttpOnly()).isTrue();
    }

    @Test
    void verifiedGoogleAccountSignsIn() throws Exception {
        var started = start();
        given(decoder.decode(any())).willReturn(idToken(started.nonce, "https://accounts.google.com", true));

        MvcResult done = mvc.perform(get("/v1/auth/google/callback")
                        .param("code", "auth-code").param("state", started.state)
                        .cookie(new Cookie(AuthController.OAUTH_COOKIE, started.state)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/en/create"))
                .andReturn();

        assertThat(done.getResponse().getCookie("bm_session")).isNotNull();
        assertThat(jdbc.sql("SELECT google_sub FROM app_user WHERE email = 'priya@example.com'").query(String.class).single())
                .isEqualTo("google-sub-1");
    }

    @Test
    void stateFromAnotherBrowserIsRefused() throws Exception {
        var started = start();
        given(decoder.decode(any())).willReturn(idToken(started.nonce, "https://accounts.google.com", true));

        mvc.perform(get("/v1/auth/google/callback")
                        .param("code", "auth-code").param("state", started.state)
                        .cookie(new Cookie(AuthController.OAUTH_COOKIE, "someone-elses-state")))
                .andExpect(header().string("Location", "http://localhost:3000/en/create?signin=failed"));
        assertThat(jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single()).isZero();
    }

    @Test
    void wrongNonceIsRefused() throws Exception {
        var started = start();
        given(decoder.decode(any())).willReturn(idToken("replayed-nonce", "https://accounts.google.com", true));

        callback(started).andExpect(header().string("Location", "http://localhost:3000/en/create?signin=failed"));
    }

    @Test
    void unverifiedEmailIsRefused() throws Exception {
        var started = start();
        given(decoder.decode(any())).willReturn(idToken(started.nonce, "https://accounts.google.com", false));

        callback(started).andExpect(header().string("Location", "http://localhost:3000/en/create?signin=failed"));
    }

    @Test
    void stateWorksOnlyOnce() throws Exception {
        var started = start();
        given(decoder.decode(any())).willReturn(idToken(started.nonce, "https://accounts.google.com", true));
        callback(started).andExpect(header().string("Location", "http://localhost:3000/en/create"));

        callback(started).andExpect(header().string("Location", "http://localhost:3000/en/create?signin=failed"));
    }

    @Test
    void googleAccountLinksToExistingEmailAccount() throws Exception {
        SignInHelper.signIn(mvc, "priya@example.com");
        var started = start();
        given(decoder.decode(any())).willReturn(idToken(started.nonce, "accounts.google.com", true));

        callback(started).andExpect(header().string("Location", "http://localhost:3000/en/create"));

        assertThat(jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single()).isEqualTo(1);
    }

    private record Started(String state, String nonce) {}

    private Started start() throws Exception {
        MvcResult start = mvc.perform(get("/v1/auth/google/start")).andReturn();
        String state = start.getResponse().getCookie(AuthController.OAUTH_COOKIE).getValue();
        String nonce = jdbc.sql("SELECT nonce FROM oauth_state").query(String.class).single();
        return new Started(state, nonce);
    }

    private org.springframework.test.web.servlet.ResultActions callback(Started s) throws Exception {
        return mvc.perform(get("/v1/auth/google/callback")
                .param("code", "auth-code").param("state", s.state)
                .cookie(new Cookie(AuthController.OAUTH_COOKIE, s.state)));
    }

    private static Jwt idToken(String nonce, String issuer, boolean emailVerified) {
        return Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(List.of(CLIENT_ID))
                .subject("google-sub-1")
                .claim("nonce", nonce)
                .claim("email", "priya@example.com")
                .claim("email_verified", emailVerified)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
