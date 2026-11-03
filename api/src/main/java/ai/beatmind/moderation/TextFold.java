package ai.beatmind.moderation;

import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.text.Transliterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds text so that tricks do not get past the block lists (REQ-SEC-04, TM-05). The same folding
 * is applied to user text and to the lexicon, so they meet in the middle:
 *
 * <ol>
 *   <li>NFKC case folding: full-width and mathematical letters become plain letters;</li>
 *   <li>zero-width, direction and other invisible format characters are removed;</li>
 *   <li>Cyrillic and Greek look-alikes become the Latin letters they imitate;</li>
 *   <li>digits and symbols inside words become letters ("k1ll", "m@@r");</li>
 *   <li>letters spelt out one at a time are joined ("k i l l", "k.i.l.l");</li>
 *   <li>Devanagari is written in Latin the way Hinglish writers spell it, and spelling variants are
 *       folded: aspirates, w and v, z and j, doubled and repeated letters, a final "a".</li>
 * </ol>
 */
final class TextFold {

    private static final Normalizer2 NFKC_CF = Normalizer2.getNFKCCasefoldInstance();
    private static final ThreadLocal<Transliterator> DEVANAGARI = ThreadLocal.withInitial(
            () -> Transliterator.getInstance("Devanagari-Latin"));
    private static final ThreadLocal<Transliterator> TO_ASCII = ThreadLocal.withInitial(
            () -> Transliterator.getInstance("Any-Latin; Latin-ASCII"));
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\u034F\\u115F\\u1160\\u3164\\uFFA0]");
    private static final Pattern DEVANAGARI_WORD = Pattern.compile("[\\u0900-\\u097F]+");
    private static final Pattern REPEATS = Pattern.compile("(.)\\1+");
    private static final Pattern CONSONANT_REPEATS = Pattern.compile("([^aeiou])\\1+");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{M}\\p{N}@$!|]+");

    /** Look-alike letters from other scripts, after case folding. */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('в', 'b'), Map.entry('е', 'e'), Map.entry('ё', 'e'), Map.entry('к', 'k'),
            Map.entry('м', 'm'), Map.entry('н', 'h'), Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('с', 'c'),
            Map.entry('т', 't'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ј', 'j'),
            Map.entry('ѕ', 's'), Map.entry('ԁ', 'd'), Map.entry('ԛ', 'q'), Map.entry('ԝ', 'w'), Map.entry('ӏ', 'l'),
            Map.entry('α', 'a'), Map.entry('β', 'b'), Map.entry('ε', 'e'), Map.entry('η', 'n'), Map.entry('ι', 'i'),
            Map.entry('κ', 'k'), Map.entry('ν', 'v'), Map.entry('ο', 'o'), Map.entry('ρ', 'p'), Map.entry('τ', 't'),
            Map.entry('υ', 'u'), Map.entry('χ', 'x'), Map.entry('ω', 'w'), Map.entry('ı', 'i'));

    /** Digits and symbols used as letters inside a word. */
    private static final Map<Character, Character> LEET = Map.ofEntries(
            Map.entry('0', 'o'), Map.entry('1', 'i'), Map.entry('3', 'e'), Map.entry('4', 'a'), Map.entry('5', 's'),
            Map.entry('7', 't'), Map.entry('8', 'b'), Map.entry('@', 'a'), Map.entry('$', 's'), Map.entry('!', 'i'),
            Map.entry('|', 'l'));

    private TextFold() {}

    /** Case-folded text with invisible characters removed and look-alikes replaced. */
    static String clean(String s) {
        String folded = NFKC_CF.normalize(INVISIBLE.matcher(s).replaceAll(""));
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            out.append(CONFUSABLES.getOrDefault(c, c));
        }
        return out.toString();
    }

    /** The folded words of a text, in order. */
    static List<String> words(String s) {
        List<String> raw = new ArrayList<>();
        Matcher m = TOKEN.matcher(clean(s));
        while (m.find()) {
            raw.add(m.group());
        }
        // Join letters spelt out one by one ("k i l l", "k.i.l.l"): three or more single letters.
        List<String> joined = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int runLength = 0;
        for (String w : raw) {
            if (w.codePointCount(0, w.length()) == 1 && Character.isLetter(w.codePointAt(0))) {
                run.append(w);
                runLength++;
                continue;
            }
            flush(joined, run, runLength);
            run.setLength(0);
            runLength = 0;
            joined.add(w);
        }
        flush(joined, run, runLength);
        List<String> out = new ArrayList<>(joined.size());
        for (String w : joined) {
            String f = fold(w);
            if (!f.isEmpty()) {
                out.add(f);
            }
        }
        return out;
    }

    private static void flush(List<String> into, StringBuilder run, int length) {
        if (length >= 3) {
            into.add(run.toString());
        } else if (length > 0) {
            for (int i = 0; i < run.length(); ) {
                int cp = run.codePointAt(i);
                into.add(new String(Character.toChars(cp)));
                i += Character.charCount(cp);
            }
        }
    }

    /** The folded words joined by single spaces, with a space at each end for whole-word search. */
    static String key(String s) {
        return " " + String.join(" ", words(s)) + " ";
    }

    /** One word in its folded Latin form. Pure numbers stay numbers (ages are checked separately). */
    static String fold(String token) {
        // "!" and "|" stand for letters only inside a word ("k!ll"); at the ends they are punctuation.
        String word = token.replaceAll("^[!|]+|[!|]+$", "");
        boolean hasLetter = word.codePoints().anyMatch(Character::isLetter);
        if (!hasLetter) {
            return word.chars().allMatch(Character::isDigit) ? word : "";
        }
        StringBuilder leet = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            leet.append(LEET.getOrDefault(c, c));
        }
        String w = latin(leet.toString()).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        w = CONSONANT_REPEATS.matcher(w).replaceAll("$1");
        w = w.replace("ph", "f").replace("kh", "k").replace("gh", "g").replace("bh", "b").replace("th", "t")
                .replace("dh", "d").replace("sh", "s").replace("chh", "ch").replace("ck", "k").replace("w", "v")
                .replace("z", "j").replace("q", "k").replace("ee", "i").replace("oo", "u");
        w = REPEATS.matcher(w).replaceAll("$1");
        if (w.length() > 2 && w.endsWith("a")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static String latin(String s) {
        Matcher m = DEVANAGARI_WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(hinglish(m.group())));
        }
        m.appendTail(out);
        return TO_ASCII.get().transliterate(out.toString());
    }

    /** Devanagari to Latin as Hinglish writers spell it: no final inherent vowel, anusvara as n. */
    private static String hinglish(String word) {
        String w = DEVANAGARI.get().transliterate(word);
        char last = word.charAt(word.length() - 1);
        boolean endsInConsonant = (last >= 'क' && last <= 'ह') || (last >= 'क़' && last <= 'य़')
                || last == '़';
        if (endsInConsonant && word.length() > 1 && w.endsWith("a")) {
            w = w.substring(0, w.length() - 1);
        }
        return w.replace("m̐", "n").replace("ṁ", "n").replace("ṃ", "n").replace("ś", "sh").replace("ṣ", "sh")
                .replaceAll("c(?!h)", "ch");
    }
}
