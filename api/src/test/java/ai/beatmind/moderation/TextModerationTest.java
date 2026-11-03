package ai.beatmind.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

/**
 * US-028: the red-team set (TC-REQ-F-50-01, TC-REQ-F-51-01) and the Unicode attack corpus
 * (TC-REQ-F-50-02, TC-REQ-SEC-04-01) pass. Blocks say why; ordinary songs, including romantic hyperbole and
 * musical words, are not blocked.
 */
class TextModerationTest {

    private static final TextModeration MODERATION = new TextModeration();
    private static final Pattern ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})|\\\\U([0-9a-fA-F]{8})");

    static Stream<Arguments> redTeam() throws IOException {
        return rows("moderation/redteam.tsv");
    }

    static Stream<Arguments> unicodeAttacks() throws IOException {
        return rows("moderation/unicode-attacks.tsv");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("redTeam")
    @org.junit.jupiter.api.Tag("TC-REQ-F-51-01")
    void redTeamSetPasses(String expected, String text) {
        assertVerdict(expected, text);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unicodeAttacks")
    @org.junit.jupiter.api.Tag("TC-REQ-F-50-02")
    void unicodeTricksDoNotGetThrough(String expected, String text) {
        assertVerdict(expected, text);
    }

    @Test
    void blocksSayWhyAndCarryACode() {
        TextModeration.Verdict v = MODERATION.check("prompt", "a song in the voice of Arijit Singh");
        assertThat(v.blocked()).isTrue();
        assertThat(v.code()).isEqualTo("moderation_artist");
        assertThat(v.field()).isEqualTo("prompt");
        assertThat(v.rule()).isEqualTo("artist:arijit sing");
        assertThat(v.message()).contains("real artists").contains("Describe the mood");
    }

    @Test
    void sexualWordsAndAChildAcrossFieldsAreBlocked() {
        TextModeration.Verdict v = MODERATION.check(Map.of("prompt", "a song about a schoolgirl",
                "lyrics", "nangi raat"));
        assertThat(v.code()).isEqualTo("moderation_sexual_minors");
    }

    @Test
    void foldingMeetsInTheMiddle() {
        assertThat(TextFold.key("रात का सफ़र")).isEqualTo(TextFold.key("raat ka safar"));
        assertThat(TextFold.key("MAAR  DAALUNGA!")).isEqualTo(TextFold.key("maar dalunga"));
        assertThat(TextFold.key("k.i.l.l")).isEqualTo(TextFold.key("kill"));
    }

    private static void assertVerdict(String expected, String text) {
        TextModeration.Verdict v = MODERATION.check("prompt", text);
        if ("allow".equals(expected)) {
            assertThat(v.blocked()).as("blocked by %s", v.rule()).isFalse();
        } else {
            assertThat(v.blocked()).as("not blocked").isTrue();
            assertThat(v.category()).as("rule %s", v.rule()).isEqualTo(expected);
            assertThat(v.message()).isNotBlank();
        }
    }

    private static Stream<Arguments> rows(String path) throws IOException {
        List<Arguments> out = new ArrayList<>();
        String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        for (String line : content.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            out.add(Arguments.of(parts[0], unescape(parts[1])));
        }
        return out.stream();
    }

    private static String unescape(String s) {
        Matcher m = ESCAPE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2), 16);
            m.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(out);
        return out.toString();
    }
}
