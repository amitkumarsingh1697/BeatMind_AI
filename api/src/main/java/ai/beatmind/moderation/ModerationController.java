package ai.beatmind.moderation;

import ai.beatmind.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A user's own moderation cases and appeals (REQ-F-59). */
@RestController
class ModerationController {

    record AppealRequest(@Size(max = 1000) String note) {}

    /** What the owner sees: no rule internals beyond the category. */
    record MyCase(UUID id, String kind, String category, UUID songId, Integer takeIdx, String status,
                  String decisionNote, java.time.OffsetDateTime createdAt) {}

    private final ModerationCases cases;

    ModerationController(ModerationCases cases) {
        this.cases = cases;
    }

    @GetMapping("/v1/moderation/cases")
    List<MyCase> mine() {
        UUID me = CurrentUser.get().orElseThrow().id();
        return cases.mine(me, 50).stream()
                .map(c -> new MyCase(c.id(), c.kind(), c.category(), c.songId(), c.takeIdx(), c.status(),
                        c.decisionNote(), c.createdAt()))
                .toList();
    }

    @PostMapping("/v1/moderation/cases/{id}/appeal")
    ResponseEntity<Map<String, String>> appeal(@PathVariable UUID id, @Valid @RequestBody AppealRequest req) {
        UUID me = CurrentUser.get().orElseThrow().id();
        if (!cases.appeal(id, me, req.note())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This decision cannot be appealed again.");
        }
        return ResponseEntity.accepted().body(Map.of("status", "appealed"));
    }
}
