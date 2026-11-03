package ai.beatmind.generation;

import ai.beatmind.identity.CurrentUser;
import ai.beatmind.provenance.ProvenanceLedger;
import ai.beatmind.provenance.ProvenanceLedger.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    static final int MIN_BPM = 60;
    static final int MAX_BPM = 200;

    /** Beat mode genres (REQ-F-04). Kept short so the model gets a clear style word. */
    static final List<String> GENRES = List.of("lofi", "hip_hop", "trap", "boom_bap", "drill", "house", "techno", "edm",
            "pop", "rnb", "afrobeats", "reggaeton", "bollywood", "bhangra", "indian_fusion", "ambient", "cinematic",
            "jazz", "rock", "funk");

    /** Instruments a creator can ask for or leave out. Includes Indian instruments (UX spec, beat mode). */
    static final List<String> INSTRUMENTS = List.of("drums", "808", "bass", "piano", "keys", "guitar", "synth", "pads",
            "strings", "brass", "flute", "tabla", "dhol", "sitar", "sarangi", "harmonium", "shehnai", "santoor",
            "percussion");

    /** Keys: a tonic and major or minor, or "any" to let the model choose. */
    static final List<String> TONICS = List.of("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B");

    /** Beat mode settings as the creator chose them (US-015). */
    record Beat(String genre, int bpm, String key, List<String> include, List<String> exclude) {
        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("genre", genre);
            m.put("bpm", bpm);
            m.put("key", key);
            m.put("include", include);
            m.put("exclude", exclude);
            return m;
        }
    }

    /** A checked request: text is NFC and within limits. {@code beat} is set in beat mode only. */
    record Request(String mode, String prompt, String lyrics, String title, String language, String vocalType,
                   boolean instrumental, int durationSeconds, Beat beat) {}

    /** The raw beat settings from the request body. */
    record BeatInput(String genre, Integer bpm, String key, List<String> include, List<String> exclude) {}

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
    private final LyricDrafts drafts;

    SongRequests(JobQueue queue, SongStore songs, Credits credits, ProvenanceLedger ledger, Entitlements entitlements,
                 JsonMapper json, LyricDrafts drafts) {
        this.drafts = drafts;
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
        return check(mode, prompt, lyrics, title, language, vocalType, instrumental, durationSeconds, null);
    }

    static Request check(String mode, String prompt, String lyrics, String title, String language, String vocalType,
                         Boolean instrumental, Integer durationSeconds, BeatInput beatInput) {
        String m = mode == null ? "vibe" : mode;
        if ("beat".equals(m)) {
            return checkBeat(prompt, title, language, durationSeconds, beatInput);
        }
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
                vocalType == null || inst ? null : vocalType, inst, durationSeconds == null ? 60 : durationSeconds, null);
    }

    /**
     * REQ-F-04: beat mode takes a genre, a BPM from 60 to 200, a key and instruments to include or
     * exclude, and always makes an instrumental. The description is optional extra colour.
     */
    static Request checkBeat(String prompt, String title, String language, Integer durationSeconds, BeatInput in) {
        if (in == null) {
            throw bad("genre", "Pick a genre for the beat.");
        }
        String genre = in.genre() == null ? "" : in.genre().strip().toLowerCase(Locale.ROOT);
        if (!GENRES.contains(genre)) {
            throw bad("genre", "Pick a genre for the beat.");
        }
        if (in.bpm() == null || in.bpm() < MIN_BPM || in.bpm() > MAX_BPM) {
            throw bad("bpm", "Choose a tempo from " + MIN_BPM + " to " + MAX_BPM + " BPM.");
        }
        String key = normaliseKey(in.key());
        if (key == null) {
            throw bad("key", "Choose a key, such as A minor, or leave it on Any key.");
        }
        List<String> include = instruments(in.include(), "include");
        List<String> exclude = instruments(in.exclude(), "exclude");
        for (String i : include) {
            if (exclude.contains(i)) {
                throw bad("exclude", "You asked to include and leave out " + i.replace('_', ' ') + ". Pick one.");
            }
        }
        String p = TextRules.nfc(prompt == null ? "" : prompt.strip());
        if (TextRules.graphemes(p) > MAX_PROMPT_GRAPHEMES) {
            throw bad("prompt", "Keep the description to " + MAX_PROMPT_GRAPHEMES + " characters.");
        }
        String t = title == null ? null : TextRules.nfc(title.strip());
        if (t != null && t.isEmpty()) {
            t = null;
        }
        if (t != null && TextRules.graphemes(t) > MAX_TITLE_GRAPHEMES) {
            throw bad("title", "Keep the title to " + MAX_TITLE_GRAPHEMES + " characters.");
        }
        Beat beat = new Beat(genre, in.bpm(), key, include, exclude);
        if (t == null) {
            t = beatTitle(beat);
        }
        if (p.isEmpty()) {
            p = beatPrompt(beat);
        }
        return new Request("beat", p, null, t, language == null ? "en" : language, null, true,
                durationSeconds == null ? 60 : durationSeconds, beat);
    }

    /** "a minor", "Am", "A min" and "A minor" all become "A minor"; blank and "any" become "any". */
    static String normaliseKey(String raw) {
        if (raw == null || raw.isBlank() || "any".equalsIgnoreCase(raw.strip())) {
            return "any";
        }
        java.util.regex.Matcher km = Pattern.compile("^([A-Ga-g])([#b♯♭]?)\\s*(major|maj|minor|min|m)?$")
                .matcher(raw.strip());
        if (!km.matches()) {
            return null;
        }
        String tonic = km.group(1).toUpperCase(Locale.ROOT) + km.group(2).replace("♯", "#").replace("♭", "b");
        tonic = switch (tonic) {
            case "Db" -> "C#";
            case "D#" -> "Eb";
            case "Gb" -> "F#";
            case "G#" -> "Ab";
            case "A#" -> "Bb";
            default -> tonic;
        };
        if (!TONICS.contains(tonic)) {
            return null;
        }
        String q = km.group(3) == null ? "major" : km.group(3);
        return tonic + ("major".equals(q) || "maj".equals(q) ? " major" : " minor");
    }

    private static List<String> instruments(List<String> raw, String field) {
        if (raw == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String r : raw) {
            String i = r == null ? "" : r.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (!INSTRUMENTS.contains(i)) {
                throw bad(field, "We do not know the instrument \"" + r + "\" yet.");
            }
            out.add(i);
        }
        if (out.size() > 8) {
            throw bad(field, "Pick up to 8 instruments.");
        }
        return new ArrayList<>(out);
    }

    static String beatTitle(Beat beat) {
        String genre = beat.genre().replace('_', ' ');
        return Character.toUpperCase(genre.charAt(0)) + genre.substring(1) + " beat, " + beat.bpm() + " BPM";
    }

    /** The style text the model gets when the creator gave no description. */
    static String beatPrompt(Beat beat) {
        StringBuilder sb = new StringBuilder("Instrumental ").append(beat.genre().replace('_', ' ')).append(" beat");
        if (!beat.include().isEmpty()) {
            sb.append(" with ").append(String.join(", ", beat.include()).replace('_', ' '));
        }
        return sb.toString();
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
        return create(me, req, ipHash, null);
    }

    /** As above; {@code lyricsDraftId} names the AI draft the lyrics came from (US-018). */
    @Transactional
    Map.Entry<UUID, UUID> create(CurrentUser me, Request req, String ipHash, UUID lyricsDraftId) {
        credits.lock(me.id());
        if (songs.suspended(me.id())) {
            throw new Refusal(HttpStatus.FORBIDDEN, "suspended", GenerationController.SUSPENDED);
        }
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
        if (req.beat() != null) {
            payload.put("beat", req.beat().asMap());
        }
        UUID jobId = queue.enqueue(req.beat() != null ? JobQueue.Kind.BEAT : JobQueue.Kind.SONG, json.writeValueAsString(payload),
                "pro".equals(me.plan()) ? 1 : 0, me.id());
        songs.create(new SongStore.NewSong(songId, me.id(), jobId, req.mode(), req.title(), tag, req.prompt(),
                req.lyrics(), req.vocalType(), req.instrumental(), req.durationSeconds(), me.plan(), ipHash,
                req.beat() == null ? null : json.writeValueAsString(req.beat().asMap())));
        credits.debit(me.id(), jobId, songId, Entitlements.CREDITS_PER_REQUEST);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("durationSeconds", req.durationSeconds());
        settings.put("instrumental", req.instrumental());
        if (req.vocalType() != null) {
            settings.put("vocalType", req.vocalType());
        }
        if (req.beat() != null) {
            settings.put("beat", req.beat().asMap());
        }
        ledger.append(songId, "prompt_submitted", Actor.HUMAN, Map.of(
                "mode", req.mode(),
                "languageTag", tag,
                "promptSha256", sha256(req.prompt()),
                "promptCharacters", TextRules.graphemes(req.prompt()),
                "settings", settings,
                "planAtCreation", me.plan()));
        if (req.lyrics() != null) {
            recordLyrics(me, songId, req.lyrics(), tag, lyricsDraftId);
        }
        ledger.append(songId, "credits_debited", Actor.SYSTEM, Map.of(
                "credits", Entitlements.CREDITS_PER_REQUEST, "jobId", jobId.toString()));
        return Map.entry(songId, jobId);
    }

    /**
     * Who wrote the lyrics (REQ-F-31): the creator, the AI, or the AI then the creator. A draft
     * counts only if it is the creator's own finished draft.
     */
    private void recordLyrics(CurrentUser me, UUID songId, String lyrics, String tag, UUID draftId) {
        LyricDrafts.Draft draft = draftId == null ? null : drafts.find(draftId, me.id())
                .filter(d -> "ready".equals(d.status()) && d.lyrics() != null).orElse(null);
        if (draft == null) {
            ledger.append(songId, "lyrics_written", Actor.HUMAN, Map.of(
                    "lyricsSha256", sha256(lyrics),
                    "lyricsCharacters", TextRules.graphemes(lyrics),
                    "languageTag", tag));
            return;
        }
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("draftId", draft.id().toString());
        ai.put("model", draft.model() == null ? "unknown" : draft.model());
        ai.put("lyricsSha256", sha256(draft.lyrics()));
        ai.put("topicSha256", sha256(draft.topic()));
        ai.put("languageTag", TextRules.languageTag(draft.language()));
        ledger.append(songId, "lyrics_generated", Actor.AI, ai);
        if (!TextRules.nfc(draft.lyrics().strip()).equals(lyrics)) {
            ledger.append(songId, "lyrics_edited", Actor.HUMAN, Map.of(
                    "lyricsSha256", sha256(lyrics),
                    "lyricsCharacters", TextRules.graphemes(lyrics),
                    "languageTag", tag));
        }
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
