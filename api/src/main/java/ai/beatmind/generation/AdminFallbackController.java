package ai.beatmind.generation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-016: today's spend on the paid fallback API, against the cap. */
@RestController
class AdminFallbackController {

    private final Fallback fallback;

    AdminFallbackController(Fallback fallback) {
        this.fallback = fallback;
    }

    @GetMapping("/v1/admin/fallback")
    Fallback.Status status() {
        return fallback.status();
    }
}
