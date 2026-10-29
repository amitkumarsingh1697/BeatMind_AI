package ai.beatmind;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Keeps the modular monolith honest (ADR-001): modules may only use each other's public API. */
class ModularityTest {

    @Test
    void moduleBoundariesHold() {
        ApplicationModules.of(BeatMindApplication.class).verify();
    }
}
