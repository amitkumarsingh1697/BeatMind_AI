package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.moderation.ModerationCases;
import ai.beatmind.moderation.TextModeration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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

/** US-018: ask the AI to write lyrics, then poll for them. API-001 with kind {@code lyrics}. */
@RestController
class LyricsController {

    record LyricsRequest(@NotNull @Size(max = 2000) String topic, @Size(max = 1000) String mood,
                         @NotNull @Pattern(regexp = "en|hi|hinglish") String language) {}

    /** {@code status} is writing, ready, blocked or failed. Lyrics are set only when ready. */
    record DraftView(UUID id, String status, String topic, String mood, String language, String languageTag,
                     String lyrics, boolean aiGenerated, String message, OffsetDateTime createdAt) {}

    private final LyricDrafts drafts;
    private final SongStore songs;
    private final Entitlements entitlements;
    private final TextModeration moderation;
    private final ModerationCases cases;

    LyricsController(LyricDrafts drafts, SongStore songs, Entitlements entitlements, TextModeration moderation,
                     ModerationCases cases) {
        this.drafts = drafts;
        this.songs = songs;
        this.entitlements = entitlements;
        this.moderation = moderation;
        this.cases = cases;
    }

    @PostMapping("/v1/lyrics")
    ResponseEntity<?> write(@Valid @RequestBody LyricsRequest req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        if (!me.profileComplete()) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "profile",
                    "Add your date of birth and accept the terms before making songs.");
        }
        if (songs.suspended(me.id())) {
            return GenerationController.problem(HttpStatus.FORBIDDEN, "suspended", GenerationController.SUSPENDED);
        }
        String topic = TextRules.nfc(req.topic().strip());
        String mood = req.mood() == null ? "" : TextRules.nfc(req.mood().strip());
        if (topic.isEmpty()) {
            return GenerationController.problem(HttpStatus.BAD_REQUEST, "invalid_topic", "Say what the song is about.");
        }
        if (TextRules.graphemes(topic) > LyricDrafts.MAX_TOPIC_GRAPHEMES) {
            return GenerationController.problem(HttpStatus.BAD_REQUEST, "invalid_topic",
                    "Keep the topic to " + LyricDrafts.MAX_TOPIC_GRAPHEMES + " characters.");
        }
        if (TextRules.graphemes(mood) > LyricDrafts.MAX_MOOD_GRAPHEMES) {
            return GenerationController.problem(HttpStatus.BAD_REQUEST, "invalid_mood",
                    "Keep the mood to " + LyricDrafts.MAX_MOOD_GRAPHEMES + " characters.");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", topic);
        fields.put("mood", mood);
        TextModeration.Verdict verdict = moderation.check(fields);
        if (verdict.blocked() && !cases.cleared(me.id(), topic + "\n" + mood)) {
            UUID caseId = cases.recordTextBlock(me.id(), "text_block", verdict, topic + "\n" + mood);
            return GenerationController.blocked(verdict, caseId);
        }
        if (drafts.today(me.id()) >= entitlements.lyricsPerDay()) {
            return GenerationController.problem(HttpStatus.TOO_MANY_REQUESTS, "lyrics_allowance",
                    "You have asked for a lot of lyrics today. Try again tomorrow, or write your own.");
        }
        LyricDrafts.Draft d = drafts.start(me.id(), topic, mood, req.language());
        return ResponseEntity.accepted().location(java.net.URI.create("/v1/lyrics/" + d.id())).body(view(d));
    }

    @GetMapping("/v1/lyrics/{id}")
    DraftView get(@PathVariable UUID id) {
        return drafts.find(id, CurrentUser.get().orElseThrow().id()).map(LyricsController::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    static DraftView view(LyricDrafts.Draft d) {
        String message = switch (d.status()) {
            case "blocked" -> "The lyrics the AI wrote did not pass our safety check. Try a different topic.";
            case "failed" -> "The lyric writer could not finish. Please try again.";
            default -> null;
        };
        return new DraftView(d.id(), d.status(), d.topic(), d.mood(), d.language(), TextRules.languageTag(d.language()),
                d.lyrics(), true, message, d.createdAt());
    }
}
