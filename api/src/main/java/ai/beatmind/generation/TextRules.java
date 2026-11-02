package ai.beatmind.generation;

import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.Transliterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text rules for songs (REQ-F-08): NFC storage, length in user-perceived characters, BCP 47
 * language tags, Latin transliteration of titles and a loose "sounds like" key for search.
 *
 * <p>ICU is used instead of the JDK because Java 21 splits some Devanagari conjuncts that browsers
 * (which also use ICU) count as one character; with ICU the page's counter and the API agree.
 */
final class TextRules {

    private static final Normalizer2 NFC = Normalizer2.getNFCInstance();
    private static final ThreadLocal<Transliterator> TO_LATIN = ThreadLocal.withInitial(
            () -> Transliterator.getInstance("Any-Latin; Latin-ASCII"));
    private static final ThreadLocal<Transliterator> DEVANAGARI = ThreadLocal.withInitial(
            () -> Transliterator.getInstance("Devanagari-Latin"));
    private static final Pattern DEVANAGARI_WORD = Pattern.compile("[\\u0900-\\u097F]+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern REPEATS = Pattern.compile("(.)\\1+");

    private TextRules() {}

    static String nfc(String s) {
        return s == null ? null : NFC.normalize(s);
    }

    /** Extended grapheme clusters, as browsers count them (Intl.Segmenter). */
    static int graphemes(String s) {
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(s);
        int count = 0;
        while (it.next() != BreakIterator.DONE) {
            count++;
        }
        return count;
    }

    /** The first {@code max} user-perceived characters. */
    static String truncate(String s, int max) {
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(s);
        int end = 0;
        for (int i = 0; i < max; i++) {
            int next = it.next();
            if (next == BreakIterator.DONE) {
                return s;
            }
            end = next;
        }
        return s.substring(0, end);
    }

    /** BCP 47 tag for a BeatMind vocal language. Hinglish is Hindi written in Latin script. */
    static String languageTag(String language) {
        return switch (language) {
            case "hi" -> "hi";
            case "hinglish" -> "hi-Latn";
            default -> "en";
        };
    }

    /**
     * Latin-script form of a title, for search, file names and screen readers without Hindi.
     * Devanagari words are written the way Hinglish writers spell them: the final inherent vowel is
     * dropped, long vowels inside a word are doubled ("रात का सफ़र" becomes "raat ka safar").
     */
    static String latin(String s) {
        var m = DEVANAGARI_WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(hinglish(m.group())));
        }
        m.appendTail(out);
        return TO_LATIN.get().transliterate(out.toString()).strip();
    }

    private static String hinglish(String word) {
        String w = DEVANAGARI.get().transliterate(word);
        char last = word.charAt(word.length() - 1);
        boolean endsInConsonant = (last >= '\u0915' && last <= '\u0939') || (last >= '\u0958' && last <= '\u095F')
                || (last == '\u093C' && word.length() > 1);
        if (endsInConsonant && word.length() > 1 && w.endsWith("a")) {
            w = w.substring(0, w.length() - 1);
        }
        w = w.replace("m̐", "n").replace("ṁ", "n").replace("ṃ", "n").replace("ś", "sh").replace("ṣ", "sh")
                .replaceAll("c(?!h)", "ch");
        // Long a inside a word reads as "aa"; at the end as "a" (कā -> ka).
        w = w.replaceAll("ā(?=\\p{L})", "aa").replace("ā", "a");
        return w;
    }

    /**
     * A loose Latin key so a search typed in Hinglish finds a Devanagari title and the other way
     * round: "raat ka safar" and "रात का सफ़र" both become "rat k safar". Aspirated consonants and
     * long vowels are folded, repeated letters collapse and a final "a" (the inherent vowel ICU
     * keeps) is dropped.
     */
    static String searchKey(String s) {
        String latin = latin(nfc(s)).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (String word : NOT_WORD.split(latin)) {
            if (word.isEmpty()) {
                continue;
            }
            String w = word.replace("ph", "f").replace("kh", "k").replace("gh", "g").replace("bh", "b")
                    .replace("th", "t").replace("dh", "d").replace("sh", "s").replace("chh", "ch")
                    .replace("w", "v").replace("z", "j").replace("ee", "i").replace("oo", "u");
            w = REPEATS.matcher(w).replaceAll("$1");
            if (w.length() > 1 && w.endsWith("a")) {
                w = w.substring(0, w.length() - 1);
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(w);
        }
        return out.toString();
    }

    /** Lower-case NFC text for plain substring search in any script. */
    static String searchText(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.append(nfc(p).toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        return out.toString();
    }
}
