package ai.beatmind.generation;

import ai.beatmind.botcheck.TurnstileVerifier;
import ai.beatmind.identity.ClientFingerprint;
import ai.beatmind.identity.CurrentUser;
import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.moderation.TextModeration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Song generation (US-012, US-017): API-001 create, API-002 status and live events. */
@RestController
class GenerationController {

    /**
     * A song request. Vibe mode needs a description; lyrics mode (US-017) needs lyrics, and the
     * prompt is then the style. Beat mode (US-015) needs a genre and a BPM and makes an
     * instrumental; {@code kind} beat is the same as {@code mode} beat.
     *
     * @param mode vibe, lyrics or beat (default vibe)
     * @param prompt vibe: what the song should feel like; lyrics: the style. Up to 500 characters.
     * @param lyrics up to 3,000 characters in Hindi, Hinglish or English (REQ-F-02)
     * @param title optional, up to 100 characters; made from the lyrics or description if blank
     * @param language vocal language: en, hi or hinglish
     * @param vocalType female, male, duet or any
     * @param durationSeconds song length, 30 to 240 seconds
     * @param turnstileToken Cloudflare Turnstile token when the bot check is on (REQ-SEC-09)
     * @param lyricsDraftId the AI draft the lyrics came from, if any (US-018), so the ledger can say
     *     which lines the AI wrote and whether the creator changed them
     * @param genre beat mode: one of {@link SongRequests#GENRES}
     * @param bpm beat mode: 60 to 200 (REQ-F-04)
     * @param key beat mode: for example "A minor", or "any"
     * @param includeInstruments beat mode: instruments to feature
     * @param excludeInstruments beat mode: instruments to leave out
     */
    record CreateRequest(
            @Pattern(regexp = "song|beat", message = "use /v1/lyrics for lyrics") String kind,
            @Pattern(regexp = "vibe|lyrics|beat") String mode,
            @Size(max = 4000) String prompt,
            @Size(max = 30000) String lyrics,
            @Size(max = 1000) String title,
            @Pattern(regexp = "en|hi|hinglish") String language,
            @Pattern(regexp = "female|male|duet|any") String vocalType,
            Boolean instrumental,
            @Min(30) @Max(240) Integer durationSeconds,
            @Size(max = 4096) String turnstileToken,
            UUID lyricsDraftId,
            @Size(max = 40) String genre,
            Integer bpm,
            @Size(max = 20) String key,
            @Size(max = 20) List<@Size(max = 40) String> includeInstruments,
            @Size(max = 20) List<@Size(max = 40) String> excludeInstruments) {

        String effectiveMode() {
            return "beat".equals(kind) ? "beat" : mode;
        }
    }

    static final String SUSPENDED = "Your account is paused while we look into a report. Contact support if you think this is a mistake.";

    private final SongRequests requests;
    private final TextModeration moderation;
    private final ModerationCases cases;
    private final GenerationStore store;
    private final GenerationEvents events;
    private final TurnstileVerifier turnstile;
    private final ClientFingerprint fingerprint;
    private final Counter requested;

    GenerationController(SongRequests requests, GenerationStore store, GenerationEvents events,
                         TurnstileVerifier turnstile, ClientFingerprint fingerprint, MeterRegistry meters,
                         TextModeration moderation, ModerationCases cases) {
        this.requests = requests;
        this.moderation = moderation;
        this.cases = cases;
        this.store = store;
        this.events = events;
        this.turnstile = turnstile;
        this.fingerprint = fingerprint;
        this.requested = Counter.builder("beatmind.generations.requested")
                .description("Song requests accepted into the queue").register(meters);
    }

    @PostMapping("/v1/generations")
    ResponseEntity<?> create(@Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        if (!me.profileComplete()) {
            return problem(HttpStatus.FORBIDDEN, "profile", "Add your date of birth and accept the terms before making songs.");
        }
        if (!turnstile.verify(req.turnstileToken(), http.getRemoteAddr())) {
            return problem(HttpStatus.FORBIDDEN, "bot_check", "We could not confirm you are a person. Reload the page and try again.");
        }
        try {
            SongRequests.Request checked = SongRequests.check(req.effectiveMode(), req.prompt(), req.lyrics(), req.title(),
                    req.language(), req.vocalType(), req.instrumental(), req.durationSeconds(),
                    new SongRequests.BeatInput(req.genre(), req.bpm(), req.key(), req.includeInstruments(),
                            req.excludeInstruments()));
            // REQ-F-50, REQ-F-51: screen everything the creator typed before anything is queued.
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("prompt", checked.prompt());
            fields.put("lyrics", checked.lyrics());
            fields.put("title", checked.title());
            TextModeration.Verdict verdict = moderation.check(fields);
            String screened = String.join("\n", checked.prompt(), checked.title(),
                    checked.lyrics() == null ? "" : checked.lyrics());
            if (verdict.blocked() && !cases.cleared(me.id(), screened)) {
                return blocked(verdict, cases.recordTextBlock(me.id(), "text_block", verdict, screened));
            }
            UUID jobId = requests.create(me, checked, fingerprint.ipHash(http), req.lyricsDraftId()).getValue();
            requested.increment();
            Generation created = store.find(jobId, me.id()).orElseThrow();
            return ResponseEntity.accepted()
                    .location(java.net.URI.create("/v1/generations/" + jobId))
                    .body(created);
        } catch (SongRequests.Refusal r) {
            return problem(r.status, r.code, r.getMessage());
        }
    }

    @GetMapping("/v1/generations")
    List<Generation> recent() {
        return store.recent(CurrentUser.get().orElseThrow().id(), 20);
    }

    @GetMapping("/v1/generations/{id}")
    Generation get(@PathVariable UUID id) {
        return store.find(id, CurrentUser.get().orElseThrow().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Server-sent events: a {@code status} event on every change, ending when the job finishes. */
    @GetMapping(path = "/v1/generations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable UUID id) {
        UUID owner = CurrentUser.get().orElseThrow().id();
        store.find(id, owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return events.stream(id, owner);
    }

    /** A blocked request: says why, and gives the case id so the creator can ask for a review. */
    static ResponseEntity<ProblemDetail> blocked(TextModeration.Verdict verdict, UUID caseId) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, verdict.message());
        body.setProperty("code", verdict.code());
        body.setProperty("field", verdict.field());
        body.setProperty("caseId", caseId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
