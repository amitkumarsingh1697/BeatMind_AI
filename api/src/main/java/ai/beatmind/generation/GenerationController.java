package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
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
import tools.jackson.databind.json.JsonMapper;

/** Song generation (US-012): API-001 create, API-002 status and live events, and take audio. */
@RestController
class GenerationController {

    static final int MAX_PROMPT_GRAPHEMES = 500;
    static final int TAKES_PER_REQUEST = 2;
    static final int MAX_ACTIVE_JOBS = 2;

    /**
     * A song request. Custom lyrics arrive with US-017 (Sprint 2) and Beat mode with US-015.
     *
     * @param language vocal language: en, hi or hinglish
     * @param durationSeconds song length, 30 to 240 seconds
     */
    record CreateRequest(
            @Pattern(regexp = "song", message = "only song is available for now") String kind,
            @NotBlank String prompt,
            @Pattern(regexp = "en|hi|hinglish") String language,
            Boolean instrumental,
            @Min(30) @Max(240) Integer durationSeconds) {}

    private final JobQueue queue;
    private final GenerationStore store;
    private final AudioStore audio;
    private final GenerationEvents events;
    private final JsonMapper json;
    private final Counter requested;

    GenerationController(JobQueue queue, GenerationStore store, AudioStore audio, GenerationEvents events,
                         JsonMapper json, MeterRegistry meters) {
        this.queue = queue;
        this.store = store;
        this.audio = audio;
        this.events = events;
        this.json = json;
        this.requested = Counter.builder("beatmind.generations.requested")
                .description("Song requests accepted into the queue").register(meters);
    }

    @PostMapping("/v1/generations")
    ResponseEntity<?> create(@Valid @RequestBody CreateRequest req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        if (!me.profileComplete()) {
            return problem(HttpStatus.FORBIDDEN, "Add your date of birth and accept the terms before making songs.");
        }
        String prompt = req.prompt().strip();
        if (graphemes(prompt) > MAX_PROMPT_GRAPHEMES) {
            return problem(HttpStatus.BAD_REQUEST, "Keep the description to " + MAX_PROMPT_GRAPHEMES + " characters.");
        }
        if (store.active(me.id()) >= MAX_ACTIVE_JOBS) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "Wait for your songs in progress to finish first.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", prompt);
        payload.put("language", req.language() == null ? "en" : req.language());
        payload.put("instrumental", Boolean.TRUE.equals(req.instrumental()));
        payload.put("durationSeconds", req.durationSeconds() == null ? 60 : req.durationSeconds());
        payload.put("takes", TAKES_PER_REQUEST);
        UUID id = queue.enqueue(JobQueue.Kind.SONG, json.writeValueAsString(payload), 0, me.id());
        requested.increment();
        Generation created = store.find(id, me.id()).orElseThrow();
        return ResponseEntity.accepted()
                .location(java.net.URI.create("/v1/generations/" + id))
                .body(created);
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

    @GetMapping("/v1/generations/{id}/takes/{index}/audio")
    ResponseEntity<InputStreamResource> takeAudio(@PathVariable UUID id, @PathVariable int index) {
        UUID owner = CurrentUser.get().orElseThrow().id();
        AudioStore.Audio file = store.takeKey(id, owner, index)
                .flatMap(audio::open)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var response = ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .contentType(MediaType.parseMediaType(file.contentType()));
        if (file.length() >= 0) {
            response.contentLength(file.length());
        }
        return response.body(new InputStreamResource(file.body()));
    }

    /** User-perceived characters, so Devanagari conjuncts count once (REQ-F-08). */
    static int graphemes(String s) {
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(s);
        int count = 0;
        while (it.next() != BreakIterator.DONE) {
            count++;
        }
        return count;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
