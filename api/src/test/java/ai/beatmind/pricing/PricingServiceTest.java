package ai.beatmind.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingServiceTest {

    private final PricingService pricing = new PricingService();

    @Test
    @Tag("TC-REQ-F-40-01")
    void indiaShowsRupeePricesIncludingGst() {
        PricingQuote quote = pricing.quote("IN", null);

        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.band()).isEqualTo(PriceBand.B4);
        assertThat(quote.variant()).isEqualTo("A");
        assertThat(quote.plans()).extracting(PricingQuote.PlanPrice::monthly)
                .containsExactly(new BigDecimal("149"), new BigDecimal("349"));
        assertThat(quote.plans()).allMatch(PricingQuote.PlanPrice::includesTax);
    }

    @Test
    void indiaVariantBShowsSunoLikePricesForThePriceTest() {
        PricingQuote quote = pricing.quote("in", "B");

        assertThat(quote.variant()).isEqualTo("B");
        assertThat(quote.plans()).extracting(PricingQuote.PlanPrice::monthly)
                .containsExactly(new BigDecimal("999"), new BigDecimal("2999"));
    }

    @Test
    void unknownVariantFallsBackToBandPrice() {
        assertThat(pricing.quote("IN", "Z").variant()).isEqualTo("A");
    }

    @Test
    void variantOnlyAppliesInIndia() {
        assertThat(pricing.quote("US", "B").variant()).isEqualTo("A");
    }

    @ParameterizedTest
    @Tag("TC-REQ-F-40-01")
    @CsvSource({
        "US, B1, 8.00, 18.00",
        "DE, B1, 8.00, 18.00",
        "MX, B2, 4.80, 10.80",
        "BR, B3, 2.80, 6.30",
        "PH, B4, 1.60, 3.60",
        "PK, B5, 0.96, 2.16",
    })
    void otherCountriesPayBasePriceTimesBandFactorInDollars(
            String country, PriceBand band, String creator, String pro) {
        PricingQuote quote = pricing.quote(country, null);

        assertThat(quote.band()).isEqualTo(band);
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.bandIsDefault()).isFalse();
        assertThat(quote.plans()).extracting(PricingQuote.PlanPrice::monthly)
                .containsExactly(new BigDecimal(creator), new BigDecimal(pro));
    }

    @Test
    void countryWithoutPaidLaunchHasNoPlans() {
        PricingQuote quote = pricing.quote("RU", null);

        assertThat(quote.paidPlansAvailable()).isFalse();
        assertThat(quote.plans()).isEmpty();
    }

    @Test
    void unlistedCountryUsesB1AndSaysSo() {
        PricingQuote quote = pricing.quote("ZA", null);

        assertThat(quote.band()).isEqualTo(PriceBand.B1);
        assertThat(quote.bandIsDefault()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL", "''", "USA", "1N", "' in '"}, nullValues = "NULL")
    void normalisesCountryCodes(String input) {
        String expected = input != null && input.strip().equalsIgnoreCase("in") ? "IN" : "XX";
        assertThat(PricingService.normaliseCountry(input)).isEqualTo(expected);
    }
}
