package ai.beatmind.moderation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Screens prompts, titles and lyrics before anything is generated, and the AI's own lyrics after
 * (US-028, REQ-F-50, REQ-F-51, REQ-SEC-04). Works the same in Hindi (Devanagari), Hinglish and
 * English because every text is folded to one Latin form first ({@link TextFold}).
 *
 * <p>Rules, in order: copying a real voice; naming a real artist (or a name that is also an
 * ordinary word, next to a style cue); slurs; a protected group next to a call to violence or
 * dehumanising words on the same line; direct threats; sexual words anywhere in a request that
 * also refers to a child or an age under 18.
 */
@Service
public class TextModeration {

    /** What a check found. Allowed verdicts have no category. */
    public record Verdict(boolean blocked, String category, String rule, String field, String message) {

        static final Verdict ALLOWED = new Verdict(false, null, null, null, null);

        /** Problem code for the page, such as {@code moderation_artist}. */
        public String code() {
            return blocked ? "moderation_" + category : null;
        }
    }

    enum Kind { ARTIST, ARTIST_CUE, CUE, CLONE, SLUR, GROUP, VIOLENCE, DEHUMANISE, THREAT, SEXUAL, MINOR }

    private static final int CUE_DISTANCE = 3;
    private static final Pattern LINE_BREAK = Pattern.compile("\\R|[.!?।॥;]+(?=\\s|$)");
    /** An age under 18 ("12 saal ki", "15 years old", "१४ साल की"). */
    private static final Pattern MINOR_AGE = Pattern.compile(
            "(?<![0-9])(1[0-7]|[1-9])\\s*-?\\s*(?:(?:saal|sal|baras|बरस|साल|वर्ष)\\s*(?:ki|ka|ke|की|का|के)"
                    + "|(?:years?|yrs?)\\s*-?\\s*old|yo|y/o)(?![a-z])");

    static final Map<String, String> MESSAGES = Map.of(
            "artist", "We can't make songs that name real artists or copy their voice or style. Describe the mood, "
                    + "instruments and era instead.",
            "hate", "This text attacks people for who they are, so we can't make it.",
            "threat", "This text threatens someone, so we can't make it.",
            "sexual_minors", "We never make sexual content involving children.");

    private final Map<Kind, List<List<String>>> terms = new EnumMap<>(Kind.class);

    public TextModeration() {
        for (Kind k : Kind.values()) {
            terms.put(k, new ArrayList<>());
        }
        var resource = new ClassPathResource("moderation/lexicon.tsv");
        try (var in = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) {
                    throw new IllegalStateException("bad lexicon line: " + line);
                }
                List<String> words = TextFold.words(parts[1]);
                if (!words.isEmpty()) {
                    terms.get(Kind.valueOf(parts[0].strip().toUpperCase(java.util.Locale.ROOT))).add(words);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Checks one text. */
    public Verdict check(String field, String text) {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(field, text);
        return check(one);
    }

    /** Checks every field of a request; the first rule that matches decides. Null texts are skipped. */
    public Verdict check(Map<String, String> fields) {
        StringBuilder all = new StringBuilder();
        for (var e : fields.entrySet()) {
            String text = e.getValue();
            if (text == null || text.isBlank()) {
                continue;
            }
            all.append(text).append('\n');
            Verdict v = checkField(e.getKey(), text);
            if (v.blocked()) {
                return v;
            }
        }
        // Sexual content and a child anywhere in the same request, across fields.
        String combined = all.toString();
        List<String> words = TextFold.words(combined);
        List<String> sexual = first(words, Kind.SEXUAL);
        if (sexual != null && (first(words, Kind.MINOR) != null || hasMinorAge(combined))) {
            return block("sexual_minors", "sexual+minor:" + String.join(" ", sexual), fieldOf(fields));
        }
        return Verdict.ALLOWED;
    }

    private Verdict checkField(String field, String text) {
        List<String> words = TextFold.words(text);
        List<String> hit;
        if ((hit = first(words, Kind.CLONE)) != null) {
            return block("artist", "clone:" + String.join(" ", hit), field);
        }
        if ((hit = first(words, Kind.ARTIST)) != null) {
            return block("artist", "artist:" + String.join(" ", hit), field);
        }
        for (List<String> name : terms.get(Kind.ARTIST_CUE)) {
            for (int at : positions(words, name)) {
                for (List<String> cue : terms.get(Kind.CUE)) {
                    for (int c : positions(words, cue)) {
                        int gap = c >= at ? c - (at + name.size() - 1) : at - (c + cue.size() - 1);
                        if (gap <= CUE_DISTANCE) {
                            return block("artist", "artist_cue:" + String.join(" ", name), field);
                        }
                    }
                }
            }
        }
        if ((hit = first(words, Kind.SLUR)) != null) {
            return block("hate", "slur:" + String.join(" ", hit), field);
        }
        for (String line : LINE_BREAK.split(text)) {
            List<String> lineWords = TextFold.words(line);
            List<String> group = first(lineWords, Kind.GROUP);
            if (group == null) {
                continue;
            }
            List<String> attack = first(lineWords, Kind.VIOLENCE);
            if (attack == null) {
                attack = first(lineWords, Kind.DEHUMANISE);
            }
            if (attack != null) {
                return block("hate", "group+" + String.join(" ", attack) + ":" + String.join(" ", group), field);
            }
        }
        if ((hit = first(words, Kind.THREAT)) != null) {
            return block("threat", "threat:" + String.join(" ", hit), field);
        }
        return Verdict.ALLOWED;
    }

    private static Verdict block(String category, String rule, String field) {
        return new Verdict(true, category, rule, field, MESSAGES.get(category));
    }

    private static String fieldOf(Map<String, String> fields) {
        return fields.size() == 1 ? fields.keySet().iterator().next() : "request";
    }

    private static boolean hasMinorAge(String text) {
        StringBuilder ascii = new StringBuilder(text.length());
        String cleaned = TextFold.clean(text);
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            ascii.append(Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : c);
        }
        return MINOR_AGE.matcher(ascii).find();
    }

    /** The first term of a kind found in the words, or null. */
    private List<String> first(List<String> words, Kind kind) {
        for (List<String> term : terms.get(kind)) {
            if (!positions(words, term).isEmpty()) {
                return term;
            }
        }
        return null;
    }

    private static List<Integer> positions(List<String> words, List<String> term) {
        List<Integer> out = new ArrayList<>();
        outer:
        for (int i = 0; i + term.size() <= words.size(); i++) {
            for (int j = 0; j < term.size(); j++) {
                if (!words.get(i + j).equals(term.get(j))) {
                    continue outer;
                }
            }
            out.add(i);
        }
        return out;
    }
}
