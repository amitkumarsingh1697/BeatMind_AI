package ai.beatmind.waitlist;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Waitlist sign-up (US-007). Contact consent is required, and so is confirmation of age 18 or over:
 * the beta is adults-only because DPDP treats under-18s as children (REQ-F-53).
 */
record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country,
        @NotBlank @Pattern(regexp = "en|hi") String locale,
        @Size(max = 200) String useCase,
        @Size(max = 64) String sourceChannel,
        @NotBlank @Pattern(regexp = "[AB]") String priceVariant,
        @AssertTrue boolean consentToContact,
        @AssertTrue boolean confirmsAdult,
        @NotBlank @Size(max = 32) String consentTextVersion,
        @Size(max = 4096) String turnstileToken) {}
