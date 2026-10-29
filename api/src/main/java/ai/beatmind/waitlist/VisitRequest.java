package ai.beatmind.waitlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Anonymous page view for the EXP-02 visit-to-sign-up rate. Carries no personal data. */
record VisitRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country,
        @NotBlank @Pattern(regexp = "[AB]") String priceVariant,
        @Size(max = 64) String sourceChannel) {}
