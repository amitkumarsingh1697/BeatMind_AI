package ai.beatmind.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in endpoints (US-009). */
@RestController
class AuthController {

    static final String OAUTH_COOKIE = "bm_oauth_state";

    record StartEmail(@NotBlank @Email @Size(max = 254) String email, @Pattern(regexp = "en|hi") String locale) {}

    record VerifyEmail(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 12) String code) {}

    private final EmailCodeService codes;
    private final UserStore users;
    private final SessionService sessions;
    private final GoogleSignIn google;
    private final AuthProperties props;

    AuthController(EmailCodeService codes, UserStore users, SessionService sessions, GoogleSignIn google,
                   AuthProperties props) {
        this.codes = codes;
        this.users = users;
        this.sessions = sessions;
        this.google = google;
        this.props = props;
    }

    /** Which sign-in methods this deployment offers. */
    @GetMapping("/v1/auth/methods")
    Map<String, Object> methods() {
        return Map.of("email", true, "google", google.enabled(), "devCodes", props.devCodes());
    }

    /**
     * Sends a sign-in code. The answer is the same whether or not the email has an account, so the
     * endpoint cannot be used to find out who uses BeatMind.
     */
    @PostMapping("/v1/auth/email/start")
    ResponseEntity<Map<String, Object>> startEmail(@Valid @RequestBody StartEmail req, HttpServletRequest http) {
        String code = codes.start(req.email(), clientIp(http), req.locale() == null ? "en" : req.locale());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sent", true);
        if (props.devCodes()) {
            body.put("devCode", code);
        }
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/v1/auth/email/verify")
    ResponseEntity<?> verifyEmail(@Valid @RequestBody VerifyEmail req) {
        Optional<String> email = codes.verify(req.email(), req.code());
        if (email.isEmpty()) {
            return problem(HttpStatus.UNAUTHORIZED, "That code is wrong or has expired. Ask for a new one.");
        }
        UserStore.User user = users.upsertByEmail(email.get(), "en");
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessions.start(user.id()))
                .body(MeController.view(user, props));
    }

    @PostMapping("/v1/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest http) {
        SessionService.tokenFrom(http).ifPresent(sessions::end);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessions.clearCookie()).build();
    }

    @GetMapping("/v1/auth/google/start")
    ResponseEntity<Void> startGoogle(@RequestParam(defaultValue = "en") String locale) {
        if (!google.enabled()) {
            return ResponseEntity.notFound().build();
        }
        GoogleSignIn.Start start = google.start("hi".equals(locale) ? "hi" : "en");
        ResponseCookie stateCookie = ResponseCookie.from(OAUTH_COOKIE, start.state())
                .httpOnly(true).secure(props.cookieSecure()).sameSite("Lax")
                .path("/v1/auth/google").maxAge(Duration.ofMinutes(10)).build();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(start.redirect())
                .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
                .build();
    }

    @GetMapping("/v1/auth/google/callback")
    ResponseEntity<Void> googleCallback(@RequestParam(required = false) String code,
                                        @RequestParam(required = false) String state,
                                        HttpServletRequest http) {
        String cookieState = null;
        if (http.getCookies() != null) {
            for (Cookie c : http.getCookies()) {
                if (OAUTH_COOKIE.equals(c.getName())) {
                    cookieState = c.getValue();
                }
            }
        }
        ResponseCookie clearState = ResponseCookie.from(OAUTH_COOKIE, "")
                .httpOnly(true).secure(props.cookieSecure()).sameSite("Lax")
                .path("/v1/auth/google").maxAge(Duration.ZERO).build();
        Optional<GoogleSignIn.Identity> identity = google.enabled()
                ? google.complete(code, state, cookieState) : Optional.empty();
        if (identity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(props.webOrigin() + "/en/create?signin=failed"))
                    .header(HttpHeaders.SET_COOKIE, clearState.toString())
                    .build();
        }
        GoogleSignIn.Identity id = identity.get();
        UserStore.User user = users.upsertByGoogle(id.sub(), id.email(), id.locale());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(props.webOrigin() + "/" + id.locale() + "/create"))
                .header(HttpHeaders.SET_COOKIE, clearState.toString())
                .header(HttpHeaders.SET_COOKIE, sessions.start(user.id()))
                .build();
    }

    @ExceptionHandler(EmailCodeService.TooManyRequests.class)
    ResponseEntity<ProblemDetail> tooMany(EmailCodeService.TooManyRequests e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    /** Cloudflare sets CF-Connecting-IP at the edge; locally the socket address is used. */
    static String clientIp(HttpServletRequest http) {
        String cf = http.getHeader("CF-Connecting-IP");
        return cf != null && !cf.isBlank() ? cf.strip() : http.getRemoteAddr();
    }
}
