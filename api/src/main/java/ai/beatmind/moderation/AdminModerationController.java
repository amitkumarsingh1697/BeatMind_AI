package ai.beatmind.moderation;

import ai.beatmind.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The moderation queue in the admin console (US-031, REQ-F-59). Role admin with MFA. */
@RestController
class AdminModerationController {

    record Decision(@NotNull @Pattern(regexp = "uphold|overturn") String decision, @Size(max = 1000) String note) {}

    private final ModerationCases cases;

    AdminModerationController(ModerationCases cases) {
        this.cases = cases;
    }

    @GetMapping("/v1/admin/moderation")
    List<ModerationCases.Case> queue(@RequestParam(required = false) String status) {
        if (status != null && !status.matches("open|appealed|upheld|overturned")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return cases.queue(status, 200);
    }

    @PostMapping("/v1/admin/moderation/{id}/decision")
    ModerationCases.Case decide(@PathVariable UUID id, @Valid @RequestBody Decision req) {
        UUID admin = CurrentUser.get().orElseThrow().id();
        return cases.decide(id, admin, "overturn".equals(req.decision()), req.note())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Already decided or not found."));
    }
}
