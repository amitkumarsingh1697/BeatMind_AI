package ai.beatmind.generation;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REQ-F-07: a vocal language that has not passed listening gate G1 (US-040) is marked experimental.
 * The list of passed languages is configuration (beatmind.languages.g1-passed), changed by the
 * person who signs off the G1 report.
 */
@RestController
class LanguagesController {

    record Language(String code, String languageTag, boolean experimental) {}

    static final List<String> CODES = List.of("hi", "hinglish", "en");

    private final Set<String> passed;

    LanguagesController(@Value("${beatmind.languages.g1-passed:}") List<String> passed) {
        this.passed = Set.copyOf(passed.stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    @GetMapping("/v1/languages")
    List<Language> languages() {
        return CODES.stream().map(c -> new Language(c, TextRules.languageTag(c), !passed.contains(c))).toList();
    }
}
