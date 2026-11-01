package ai.beatmind.identity;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in settings ({@code beatmind.auth.*}).
 *
 * @param pepper secret used to HMAC sign-in codes and IP addresses. Blank means a random one per
 *     start, which is fine locally but signs everyone's codes out on restart.
 * @param devCodes local development only: return the sign-in code in the API response instead of
 *     emailing it. Never turn on outside a developer's machine.
 * @param cookieSecure mark the session cookie Secure. Off only for plain-http local development.
 * @param sessionTtl absolute session lifetime.
 * @param sessionIdle a session unused for this long ends.
 * @param webOrigin where the web app lives, for redirects after Google sign-in.
 * @param adminEmails accounts with the admin role (US-013 dead-letter view).
 * @param google Google OpenID Connect client; blank client id turns Google sign-in off.
 */
@ConfigurationProperties("beatmind.auth")
record AuthProperties(
        String pepper,
        boolean devCodes,
        boolean cookieSecure,
        Duration sessionTtl,
        Duration sessionIdle,
        String webOrigin,
        List<String> adminEmails,
        Google google) {

    AuthProperties {
        pepper = pepper == null ? "" : pepper;
        sessionTtl = sessionTtl == null ? Duration.ofDays(14) : sessionTtl;
        sessionIdle = sessionIdle == null ? Duration.ofDays(3) : sessionIdle;
        webOrigin = webOrigin == null || webOrigin.isBlank() ? "http://localhost:3000" : webOrigin;
        adminEmails = adminEmails == null ? List.of()
                : adminEmails.stream().map(String::strip).filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        google = google == null ? new Google("", "", "") : google;
    }

    boolean isAdmin(String email) {
        return adminEmails.contains(email.toLowerCase(Locale.ROOT));
    }

    record Google(String clientId, String clientSecret, String redirectUri) {
        Google {
            clientId = clientId == null ? "" : clientId.strip();
            clientSecret = clientSecret == null ? "" : clientSecret.strip();
            redirectUri = redirectUri == null ? "" : redirectUri.strip();
        }

        boolean enabled() {
            return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUri.isEmpty();
        }
    }
}
