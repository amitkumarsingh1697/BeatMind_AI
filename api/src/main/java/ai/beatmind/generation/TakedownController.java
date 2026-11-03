package ai.beatmind.generation;

import ai.beatmind.botcheck.TurnstileVerifier;
import ai.beatmind.identity.ClientFingerprint;
import ai.beatmind.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reports, takedowns and appeals (US-032, API-013, REQ-F-54, REQ-F-59). Anyone can report a song,
 * signed in or not; the song's owner can appeal a removal; admins work the queue by deadline.
 */
@RestController
class TakedownController {

    /**
     * A report from the public form.
     *
     * @param target the song link, share link or certificate id
     * @param accurate the reporter confirms the information is accurate and they may act (policy 2.4)
     */
    record ReportRequest(
            @NotBlank @Size(max = 500) String target,
            @NotNull @Pattern(regexp = "rights_holder|user|government_order|court_order") String reporterKind,
            @NotBlank @Size(max = 200) String reporterName,
            @NotBlank @Email @Size(max = 320) String reporterEmail,
            @NotNull @Pattern(regexp = "copyright|voice|name_or_likeness|privacy|unlawful|other") String rightClaimed,
            @NotBlank @Size(max = 5000) String description,
            @Size(max = 1000) @Pattern(regexp = "^$|https?://\\S+") String evidenceUrl,
            @Size(max = 200) String orderReference,
            @AssertTrue boolean accurate,
            @Size(max = 4096) String turnstileToken) {}

    record Appeal(@NotBlank @Size(max = 2000) String note) {}

    record Decision(@NotNull @Pattern(regexp = "disable|reject") String decision, @Size(max = 2000) String note) {}

    record AppealDecision(@NotNull @Pattern(regexp = "restore|uphold") String decision, @Size(max = 2000) String note) {}

    private final Takedowns takedowns;
    private final SongStore songs;
    private final TurnstileVerifier turnstile;
    private final ClientFingerprint fingerprint;

    TakedownController(Takedowns takedowns, SongStore songs, TurnstileVerifier turnstile, ClientFingerprint fingerprint) {
        this.takedowns = takedowns;
        this.songs = songs;
        this.turnstile = turnstile;
        this.fingerprint = fingerprint;
    }

    /** API-013. Public: rights holders and authorities do not need an account. */
    @PostMapping("/v1/reports")
    ResponseEntity<?> report(@Valid @RequestBody ReportRequest req, HttpServletRequest http) {
        if (!turnstile.verify(req.turnstileToken(), http.getRemoteAddr())) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "bot_check",
                    "We could not confirm you are a person. Reload the page and try again.");
        }
        try {
            UUID me = CurrentUser.get().map(CurrentUser::id).orElse(null);
            UUID id = takedowns.report(new Takedowns.NewReport(req.target().strip(), req.reporterKind(),
                    req.reporterName().strip(), req.reporterEmail().strip(), req.rightClaimed(), req.description().strip(),
                    blankToNull(req.evidenceUrl()), blankToNull(req.orderReference())), me, fingerprint.ipHash(http));
            Takedowns.Takedown t = takedowns.find(id).orElseThrow();
            return ResponseEntity.accepted().body(Map.of(
                    "id", id,
                    "reference", "TD-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                    "songFound", t.songId() != null,
                    "acknowledgeBy", t.ackDueAt(),
                    "actBy", t.actionDueAt()));
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    /** The owner asks a person to review a removal (REQ-F-59, 72 hours). */
    @PostMapping("/v1/songs/{id}/takedown/appeal")
    ResponseEntity<?> appeal(@PathVariable UUID id, @Valid @RequestBody Appeal req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        songs.find(id, me.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!takedowns.appeal(id, req.note())) {
            return GenerationController.problem(HttpStatus.CONFLICT, "not_appealable",
                    "This song has no removal to appeal, or you have already appealed it.");
        }
        return ResponseEntity.accepted().body(takedowns.forOwner(id).orElseThrow());
    }

    @GetMapping("/v1/admin/takedowns")
    List<Takedowns.Takedown> queue(@RequestParam(required = false) String status) {
        if (status != null && !status.matches("received|acknowledged|actioned|rejected|appealed|restored")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return takedowns.queue(status);
    }

    @GetMapping("/v1/admin/takedowns/overdue")
    Takedowns.Overdue overdue() {
        return takedowns.overdue();
    }

    @PostMapping("/v1/admin/takedowns/{id}/acknowledge")
    Takedowns.Takedown acknowledge(@PathVariable UUID id) {
        return takedowns.acknowledge(id, admin())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Already acknowledged or not found."));
    }

    @PostMapping("/v1/admin/takedowns/{id}/decision")
    ResponseEntity<?> decide(@PathVariable UUID id, @Valid @RequestBody Decision req) {
        try {
            return takedowns.decide(id, admin(), "disable".equals(req.decision()), req.note())
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Already decided or not found."));
        } catch (SongRequests.Refusal r) {
            return GenerationController.problem(r.status, r.code, r.getMessage());
        }
    }

    @PostMapping("/v1/admin/takedowns/{id}/appeal-decision")
    Takedowns.Takedown decideAppeal(@PathVariable UUID id, @Valid @RequestBody AppealDecision req) {
        return takedowns.decideAppeal(id, admin(), "restore".equals(req.decision()), req.note())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No open appeal."));
    }

    private static UUID admin() {
        return CurrentUser.get().orElseThrow().id();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
