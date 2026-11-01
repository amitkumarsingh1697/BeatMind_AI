package ai.beatmind.identity;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Checks Google ID token signatures and expiry against Google's published keys. Issuer, audience
 * and nonce are checked in {@link GoogleSignIn}. Keys are fetched on first use, not at start-up.
 */
@Component
class GoogleIdTokenDecoder {

    static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";

    private final JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URL).build();

    Jwt decode(String idToken) {
        return decoder.decode(idToken);
    }
}
