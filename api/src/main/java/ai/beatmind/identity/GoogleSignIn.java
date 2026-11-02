package ai.beatmind.identity;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google OpenID Connect sign-in (INT-11): authorization code flow with PKCE, a one-time state
 * bound to the browser by cookie, and a nonce checked in the ID token.
 */
@Service
class GoogleSignIn {

    static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    record Start(URI redirect, String state) {}

    record Identity(String sub, String email, String locale) {}

    private final AuthProperties props;
    private final Secrets secrets;
    private final JdbcClient jdbc;
    private final GoogleTokenClient tokens;
    private final GoogleIdTokenDecoder idTokens;

    GoogleSignIn(AuthProperties props, Secrets secrets, JdbcClient jdbc, GoogleTokenClient tokens,
                 GoogleIdTokenDecoder idTokens) {
        this.props = props;
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.idTokens = idTokens;
    }

    boolean enabled() {
        return props.google().enabled();
    }

    Start start(String locale) {
        String state = secrets.token();
        String nonce = secrets.token();
        String verifier = secrets.token();
        jdbc.sql("DELETE FROM oauth_state WHERE expires_at < now()").update();
        jdbc.sql("""
                INSERT INTO oauth_state (state_hash, nonce, code_verifier, locale, expires_at)
                VALUES (:hash, :nonce, :verifier, :locale, now() + interval '10 minutes')
                """)
                .param("hash", secrets.sha256(state))
                .param("nonce", nonce)
                .param("verifier", verifier)
                .param("locale", locale)
                .update();
        URI uri = UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", props.google().clientId())
                .queryParam("redirect_uri", props.google().redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", secrets.sha256Base64Url(verifier))
                .queryParam("code_challenge_method", "S256")
                .queryParam("prompt", "select_account")
                .encode()
                .build()
                .toUri();
        return new Start(uri, state);
    }

    /**
     * Completes sign-in. {@code cookieState} is the state stored in the browser at start; it must
     * match the state Google sent back, which stops another site from signing a user in to an
     * attacker's account.
     */
    @Transactional
    Optional<Identity> complete(String code, String state, String cookieState) {
        if (code == null || state == null || cookieState == null || !Secrets.constantTimeEquals(state, cookieState)) {
            return Optional.empty();
        }
        record Pending(String nonce, String verifier, String locale) {}
        Optional<Pending> pending = jdbc.sql("""
                DELETE FROM oauth_state WHERE state_hash = :hash AND expires_at > now()
                RETURNING nonce, code_verifier, locale
                """)
                .param("hash", secrets.sha256(state))
                .query((rs, n) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        String idToken = tokens.exchange(code, pending.get().verifier());
        Jwt jwt;
        try {
            jwt = idTokens.decode(idToken);
        } catch (JwtException e) {
            return Optional.empty();
        }
        String issuer = jwt.getClaimAsString("iss");
        boolean ok = issuer != null && ISSUERS.contains(issuer)
                && jwt.getAudience() != null && jwt.getAudience().contains(props.google().clientId())
                && pending.get().nonce().equals(jwt.getClaimAsString("nonce"))
                && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                && jwt.getClaimAsString("email") != null;
        if (!ok) {
            return Optional.empty();
        }
        return Optional.of(new Identity(jwt.getSubject(), jwt.getClaimAsString("email"), pending.get().locale()));
    }

    /** Swaps an authorization code for an ID token at Google's token endpoint. */
    @Service
    static class GoogleTokenClient {

        private final AuthProperties props;
        private final RestClient http;

        GoogleTokenClient(AuthProperties props) {
            this.props = props;
            this.http = RestClient.create();
        }

        String exchange(String code, String verifier) {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("code", code);
            form.add("client_id", props.google().clientId());
            form.add("client_secret", props.google().clientSecret());
            form.add("redirect_uri", props.google().redirectUri());
            form.add("grant_type", "authorization_code");
            form.add("code_verifier", verifier);
            Map<?, ?> body = http.post().uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object idToken = body == null ? null : body.get("id_token");
            if (!(idToken instanceof String s)) {
                throw new IllegalStateException("Google token response had no id_token");
            }
            return s;
        }
    }
}
