package ai.beatmind.waitlist;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Waitlist endpoints for US-007 and EXP-02. */
@RestController
class WaitlistController {

    private final WaitlistService waitlist;
    private final TurnstileVerifier turnstile;

    WaitlistController(WaitlistService waitlist, TurnstileVerifier turnstile) {
        this.waitlist = waitlist;
        this.turnstile = turnstile;
    }

    @PostMapping("/v1/waitlist")
    ResponseEntity<?> signUp(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        if (!turnstile.verify(req.turnstileToken(), http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Bot check failed"));
        }
        waitlist.signUp(req);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/v1/waitlist/visits")
    ResponseEntity<Void> visit(@Valid @RequestBody VisitRequest req) {
        waitlist.recordVisit(req);
        return ResponseEntity.noContent().build();
    }
}
