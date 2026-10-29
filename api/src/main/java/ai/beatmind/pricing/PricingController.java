package ai.beatmind.pricing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API-008: local plan prices for a country. */
@RestController
class PricingController {

    private final PricingService pricing;

    PricingController(PricingService pricing) {
        this.pricing = pricing;
    }

    /**
     * The country comes from the query, else from Cloudflare's CF-IPCountry header (ARCH-09).
     */
    @GetMapping("/v1/pricing")
    PricingQuote pricing(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String variant,
            @RequestHeader(name = "CF-IPCountry", required = false) String edgeCountry) {
        return pricing.quote(country != null ? country : edgeCountry, variant);
    }
}
