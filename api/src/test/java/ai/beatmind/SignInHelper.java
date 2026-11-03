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
        jdbc.sql("""
                TRUNCATE generation_job, song, take, provenance_event, credit_ledger, app_setting, user_session,
                         email_code, oauth_state, admin_audit, moderation_case, lyric_draft, certificate, user_mfa,
                         mfa_recovery_code, similarity_reference, similarity_hash, takedown_request, share_link, app_user CASCADE
                """).update();
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

    /**
     * Signs in an admin and passes the second factor (US-031): sets up TOTP and confirms it with a
     * code computed from the secret, as an authenticator app would.
     */
    public static Cookie signInAdmin(MockMvc mvc, String email) throws Exception {
        Cookie cookie = signInAdult(mvc, email);
        String setup = mvc.perform(post("/v1/me/mfa/setup").cookie(cookie)).andReturn().getResponse().getContentAsString();
        String secret = setup.replaceAll(".*\"secret\":\"([A-Z2-7]+)\".*", "$1");
        MvcResult confirm = mvc.perform(post("/v1/me/mfa/confirm").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(totp(secret, java.time.Instant.now())))).andReturn();
        if (confirm.getResponse().getStatus() != 200) {
            throw new IllegalStateException("MFA confirm failed: " + confirm.getResponse().getContentAsString());
        }
        return cookie;
    }

    /** RFC 6238 code for a base32 secret, as an authenticator app computes it. */
    public static String totp(String base32Secret, java.time.Instant at) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream key = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : base32Secret.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                key.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA1"));
        byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(at.getEpochSecond() / 30).array());
        int o = h[h.length - 1] & 0x0f;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return "%06d".formatted(bin % 1_000_000);
    }

    /** Signs in and completes the 18+ profile step. */
    public static Cookie signInAdult(MockMvc mvc, String email) throws Exception {
        Cookie cookie = signIn(mvc, email);
        mvc.perform(post("/v1/me/profile").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"birthDate\":\"1995-04-12\",\"acceptTerms\":true,\"termsVersion\":\"2026-11-beta\"}"));
        return cookie;
    }
}
