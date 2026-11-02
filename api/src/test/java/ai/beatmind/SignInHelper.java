package ai.beatmind;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Signs a test user in through the real email-code endpoints (dev codes are on in tests). */
public final class SignInHelper {

    private SignInHelper() {}

    /** Removes all accounts, sessions, codes and jobs. */
    public static void reset(JdbcClient jdbc) {
        jdbc.sql("TRUNCATE generation_job, song, take, provenance_event, credit_ledger, app_setting, user_session, email_code, oauth_state, admin_audit, app_user CASCADE").update();
    }

    public static String devCode(MockMvc mvc, String email) throws Exception {
        MvcResult start = mvc.perform(post("/v1/auth/email/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"locale\":\"en\"}".formatted(email)))
                .andReturn();
        String body = start.getResponse().getContentAsString();
        return body.replaceAll(".*\"devCode\":\"(\\d{6})\".*", "$1");
    }

    /** Signs in and returns the session cookie. The profile step is not done yet. */
    public static Cookie signIn(MockMvc mvc, String email) throws Exception {
        String code = devCode(mvc, email);
        MvcResult verify = mvc.perform(post("/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
                .andReturn();
        Cookie cookie = verify.getResponse().getCookie("bm_session");
        if (cookie == null) {
            throw new IllegalStateException("sign-in failed: " + verify.getResponse().getContentAsString());
        }
        return new Cookie(cookie.getName(), cookie.getValue());
    }

    /** Signs in and completes the 18+ profile step. */
    public static Cookie signInAdult(MockMvc mvc, String email) throws Exception {
        Cookie cookie = signIn(mvc, email);
        mvc.perform(post("/v1/me/profile").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"birthDate\":\"1995-04-12\",\"acceptTerms\":true,\"termsVersion\":\"2026-11-beta\"}"));
        return cookie;
    }
}
