package ai.beatmind.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * Local plan prices for one country (API-008).
 *
 * @param band null when paid plans are not offered in the country
 * @param bandIsDefault true when the country is not in the band table and B1 was used
 */
public record PricingQuote(
        String country,
        PriceBand band,
        boolean bandIsDefault,
        boolean paidPlansAvailable,
        String currency,
        String variant,
        List<PlanPrice> plans) {

    public record PlanPrice(String plan, BigDecimal monthly, boolean includesTax) {}
}
