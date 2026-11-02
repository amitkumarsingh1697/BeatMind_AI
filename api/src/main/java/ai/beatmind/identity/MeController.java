package ai.beatmind.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's account and the 18+ profile step (REQ-F-53). */
@RestController
class MeController {

    static final int MINIMUM_AGE = 18;

    record Profile(@NotNull LocalDate birthDate, @AssertTrue boolean acceptTerms,
                   @NotBlank @Size(max = 40) String termsVersion) {}

    private final UserStore users;
    private final SessionService sessions;
    private final AuthProperties props;
    private final Clock clock = Clock.systemUTC();

    MeController(UserStore users, SessionService sessions, AuthProperties props) {
        this.users = users;
        this.sessions = sessions;
        this.props = props;
    }

    @GetMapping("/v1/me")
    ResponseEntity<?> me() {
        return CurrentUser.get()
                .flatMap(u -> users.find(u.id()))
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(view(u, props)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /**
     * Records the declared date of birth. Under 18, the account and its session are deleted at
     * once: the beta does not keep children's data (DPDP Act, COMP-IN-04).
     */
    @PostMapping("/v1/me/profile")
    ResponseEntity<?> completeProfile(@Valid @RequestBody Profile req, HttpServletRequest http) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        LocalDate today = LocalDate.now(clock);
        if (req.birthDate().isAfter(today)) {
            return AuthController.problem(HttpStatus.BAD_REQUEST, "Date of birth is in the future.");
        }
        if (!isAdult(req.birthDate(), today)) {
            users.delete(me.id());
            SessionService.tokenFrom(http).ifPresent(sessions::end);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.SET_COOKIE, sessions.clearCookie())
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                            "BeatMind is for people aged 18 and over during the beta."));
        }
        users.completeProfile(me.id(), req.birthDate(), req.termsVersion());
        return ResponseEntity.ok(view(users.find(me.id()).orElseThrow(), props));
    }

    static boolean isAdult(LocalDate birthDate, LocalDate today) {
        return Period.between(birthDate, today).getYears() >= MINIMUM_AGE;
    }

    static Map<String, Object> view(UserStore.User user, AuthProperties props) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.id());
        body.put("email", user.email());
        body.put("needsProfile", !user.profileComplete());
        body.put("admin", props.isAdmin(user.email()));
        body.put("plan", user.plan());
        return body;
    }
}
