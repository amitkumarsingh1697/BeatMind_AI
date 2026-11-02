package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a song request into a song, a queued job, a credit debit and the first ledger events, in
 * one transaction, after the caps and velocity rules (US-017, US-030, REQ-F-55, REQ-SEC-09).
 */
@Service
class SongRequests {

    static final int MAX_PROMPT_GRAPHEMES = 500;
    static final int MAX_LYRICS_GRAPHEMES = 3000;
    static final int MAX_TITLE_GRAPHEMES = 100;
    static final int DEFAULT_TITLE_GRAPHEMES = 60;

    private static final Pattern SECTION_TAG = Pattern.compile("^\\s*\\[[^\\]]*\\]\\s*$");

    /** A checked request: text is NFC and within limits. */
    record Request(String mode, String prompt, String lyrics, String title, String language, String vocalType,
                   boolean instrumental, int durationSeconds) {}

    /** Why a request was refused. {@code code} lets the page show the right help. */
    static final class Refusal extends RuntimeException {
        final HttpStatus status;
        final String code;

        Refusal(HttpStatus status, String code, String detail) {
            super(detail);
            this.status = status;
            this.code = code;
        }
    }

    private final JobQueue queue;
    private final SongStore songs;
    private final Credits credits;
    private final ProvenanceLedger ledger;
    private final Entitlements entitlements;
    private final JsonMapper json;

    SongRequests(JobQueue queue, SongStore songs, Credits credits, ProvenanceLedger ledger, Entitlements entitlements,
                 JsonMapper json) {
        this.queue = queue;
        this.songs = songs;
        this.credits = credits;
        this.ledger = ledger;
        this.entitlements = entitlements;
        this.json = json;
    }

    /** Validates and normalises the raw request (REQ-F-02, REQ-F-08). */
    static Request check(String mode, String prompt, String lyrics, String title, String language, String vocalType,
                         Boolean instrumental, Integer durationSeconds) {
        String m = mode == null ? "vibe" : mode;
        String p = TextRules.nfc(prompt == null ? "" : prompt.strip());
        String l = lyrics == null ? null : TextRules.nfc(lyrics.strip());
        String t = title == null ? null : TextRules.nfc(title.strip());
        boolean inst = Boolean.TRUE.equals(instrumental);
        if ("vibe".equals(m) && p.isEmpty()) {
            throw bad("prompt", "Describe the song you want.");
        }
        if (TextRules.graphemes(p) > MAX_PROMPT_GRAPHEMES) {
            throw bad("prompt", "Keep the " + ("lyrics".equals(m) ? "style" : "description") + " to "
                    + MAX_PROMPT_GRAPHEMES + " characters.");
        }
        if ("lyrics".equals(m)) {
            if (l == null || l.isEmpty()) {
                throw bad("lyrics", "Paste or write your lyrics.");
            }
            if (TextRules.graphemes(l) > MAX_LYRICS_GRAPHEMES) {
                throw bad("lyrics", "Keep the lyrics to " + String.format("%,d", MAX_LYRICS_GRAPHEMES) + " characters.");
            }
            if (inst) {
                throw bad("instrumental", "Turn off instrumental so your lyrics can be sung.");
            }
        } else {
            l = null;
        }
        if (t != null && t.isEmpty()) {
            t = null;
        }
        if (t != null && TextRules.graphemes(t) > MAX_TITLE_GRAPHEMES) {
            throw bad("title", "Keep the title to " + MAX_TITLE_GRAPHEMES + " characters.");
        }
        if (t == null) {
            t = defaultTitle(m, p, l);
        }
        return new Request(m, p, l, t, language == null ? "en" : language,
                vocalType == null || inst ? null : vocalType, inst, durationSeconds == null ? 60 : durationSeconds);
    }

    /** First lyric line (skipping [Verse] tags), else the description, cut to 60 characters. */
    static String defaultTitle(String mode, String prompt, String lyrics) {
        String source = prompt;
        if ("lyrics".equals(mode) && lyrics != null) {
            for (String line : lyrics.split("\\R")) {
                if (!line.isBlank() && !SECTION_TAG.matcher(line).matches()) {
                    source = line.strip();
                    break;
                }
            }
        }
        if (source == null || source.isBlank()) {
            return "Untitled song";
        }
        String cut = TextRules.truncate(source, DEFAULT_TITLE_GRAPHEMES).strip();
        return cut.replaceAll("[\\s,.;:!?-]+$", "");
    }

    /** Checks caps and queues the song. Returns the new song's id and its job's id. */
    @Transactional
    Map.Entry<UUID, UUID> create(CurrentUser me, Request req, String ipHash) {
        credits.lock(me.id());
        if (songs.active(me.id()) >= Entitlements.concurrency(me.plan())) {
            int n = Entitlements.concurrency(me.plan());
            throw new Refusal(HttpStatus.TOO_MANY_REQUESTS, "concurrency", n == 1
                    ? "Wait for your song in progress to finish first."
                    : "Wait for one of your " + n + " songs in progress to finish first.");
        }
        if (songs.recentFromUser(me.id()) >= entitlements.userRequestsPerTenMinutes()) {
            throw new Refusal(HttpStatus.TOO_MANY_REQUESTS, "velocity",
                    "That is a lot of songs in a few minutes. Please wait a little and try again.");
        }
        if (ipHash != null && songs.recentFromIp(ipHash) >= entitlements.ipRequestsPerHour()) {
            throw new Refusal(HttpStatus.TOO_MANY_REQUESTS, "velocity",
                    "Too many songs were requested from your network in the last hour. Please try again later.");
        }
        Credits.Allowance allowance = credits.allowance(me.id(), me.plan());
        if (allowance.remaining() < Entitlements.CREDITS_PER_REQUEST) {
            throw new Refusal(HttpStatus.TOO_MANY_REQUESTS, "allowance", "day".equals(allowance.window())
                    ? "You have used today's free songs. Your allowance resets at midnight India time."
                    : "You have used this month's credits.");
        }

        UUID songId = UUID.randomUUID();
        String tag = TextRules.languageTag(req.language());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("songId", songId.toString());
        payload.put("mode", req.mode());
        payload.put("title", req.title());
        payload.put("prompt", req.prompt());
        if (req.lyrics() != null) {
            payload.put("lyrics", req.lyrics());
        }
        payload.put("language", req.language());
        payload.put("languageTag", tag);
        payload.put("vocalType", req.vocalType());
        payload.put("instrumental", req.instrumental());
        payload.put("durationSeconds", req.durationSeconds());
        payload.put("takes", Entitlements.TAKES_PER_REQUEST);
        payload.put("rights", Entitlements.paid(me.plan()) ? "user_owned" : "bm_owned");
        UUID jobId = queue.enqueue(JobQueue.Kind.SONG, json.writeValueAsString(payload),
                "pro".equals(me.plan()) ? 1 : 0, me.id());
        songs.create(new SongStore.NewSong(songId, me.id(), jobId, req.mode(), req.title(), tag, req.prompt(),
                req.lyrics(), req.vocalType(), req.instrumental(), req.durationSeconds(), me.plan(), ipHash));
        credits.debit(me.id(), jobId, songId, Entitlements.CREDITS_PER_REQUEST);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("durationSeconds", req.durationSeconds());
        settings.put("instrumental", req.instrumental());
        if (req.vocalType() != null) {
            settings.put("vocalType", req.vocalType());
        }
        ledger.append(songId, "prompt_submitted", Actor.HUMAN, Map.of(
                "mode", req.mode(),
                "languageTag", tag,
                "promptSha256", sha256(req.prompt()),
                "promptCharacters", TextRules.graphemes(req.prompt()),
                "settings", settings,
                "planAtCreation", me.plan()));
        if (req.lyrics() != null) {
            ledger.append(songId, "lyrics_written", Actor.HUMAN, Map.of(
                    "lyricsSha256", sha256(req.lyrics()),
                    "lyricsCharacters", TextRules.graphemes(req.lyrics()),
                    "languageTag", tag));
        }
        ledger.append(songId, "credits_debited", Actor.SYSTEM, Map.of(
                "credits", Entitlements.CREDITS_PER_REQUEST, "jobId", jobId.toString()));
        return Map.entry(songId, jobId);
    }

    private static Refusal bad(String field, String detail) {
        return new Refusal(HttpStatus.BAD_REQUEST, "invalid_" + field, detail);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
