package ai.beatmind.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Second-factor enrolment and checks for the signed-in user (US-031, REQ-SEC-06). A session that
 * passes the check is marked, which is what unlocks the admin role for admin accounts.
 */
@RestController
class MfaController {

    record Code(@NotBlank @Size(max = 32) String code) {}

    private final MfaService mfa;
    private final SessionService sessions;
    private final AuthProperties props;

    MfaController(MfaService mfa, SessionService sessions, AuthProperties props) {
        this.mfa = mfa;
        this.sessions = sessions;
        this.props = props;
    }

    @GetMapping("/v1/me/mfa")
    Map<String, Object> status(HttpServletRequest http) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        MfaService.Status s = mfa.status(me.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enrolled", s.enrolled());
        body.put("required", props.isAdmin(me.email()));
        body.put("verified", verified(http));
        body.put("recoveryCodesLeft", s.enrolled() ? mfa.recoveryCodesLeft(me.id()) : 0);
        return body;
    }

    @PostMapping("/v1/me/mfa/setup")
    ResponseEntity<?> setup() {
        CurrentUser me = CurrentUser.get().orElseThrow();
        return mfa.setup(me.id(), me.email())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> AuthController.problem(HttpStatus.CONFLICT,
                        "A second factor is already set up. Use it, or a recovery code, to continue."));
    }

    @PostMapping("/v1/me/mfa/confirm")
    ResponseEntity<?> confirm(@Valid @RequestBody Code req, HttpServletRequest http) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        return mfa.confirm(me.id(), req.code())
                .<ResponseEntity<?>>map(codes -> {
                    SessionService.tokenFrom(http).ifPresent(sessions::markMfa);
                    return ResponseEntity.ok(Map.of("recoveryCodes", codes));
                })
                .orElseGet(() -> AuthController.problem(HttpStatus.BAD_REQUEST,
                        "That code did not match. Check the time on your phone and enter the newest code."));
    }

    @PostMapping("/v1/me/mfa/verify")
    ResponseEntity<?> verify(@Valid @RequestBody Code req, HttpServletRequest http) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        return switch (mfa.verify(me.id(), req.code())) {
            case OK -> {
                SessionService.tokenFrom(http).ifPresent(sessions::markMfa);
                yield ResponseEntity.ok(Map.of("verified", true));
            }
            case LOCKED -> AuthController.problem(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong codes. Wait 15 minutes and try again.");
            case NOT_ENROLLED -> AuthController.problem(HttpStatus.CONFLICT, "Set up a second factor first.");
            case WRONG -> AuthController.problem(HttpStatus.BAD_REQUEST,
                    "That code did not match. Enter the newest code from your app, or a recovery code.");
        };
    }

    private boolean verified(HttpServletRequest http) {
        return SessionService.tokenFrom(http).flatMap(sessions::resolve).map(s -> s.mfaAt() != null).orElse(false);
    }
}
