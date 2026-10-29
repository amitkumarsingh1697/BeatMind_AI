package ai.beatmind.pricing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Works out local plan prices (pricing-and-financial-model.md section 1).
 *
 * <p>India uses fixed rupee price points including GST. EXP-02 shows Indian visitors one of two
 * variants: A is the band price, B is Suno-like pricing (SRC-111). Other countries see the US base
 * price times the band factor, in US dollars, before sales tax.
 */
@Service
public class PricingService {

    public static final String VARIANT_BAND = "A";
    public static final String VARIANT_SUNO_LIKE = "B";

    static final BigDecimal BASE_CREATOR_USD = new BigDecimal("8.00");
    static final BigDecimal BASE_PRO_USD = new BigDecimal("18.00");

    private static final Map<String, List<BigDecimal>> INDIA_INR = Map.of(
            VARIANT_BAND, List.of(new BigDecimal("149"), new BigDecimal("349")),
            VARIANT_SUNO_LIKE, List.of(new BigDecimal("999"), new BigDecimal("2999")));

    private final Map<String, Optional<PriceBand>> bands;

    public PricingService() {
        this.bands = loadBands("pricing/country-bands.csv");
    }

    public PricingQuote quote(String countryCode, String requestedVariant) {
        String country = normaliseCountry(countryCode);
        Optional<PriceBand> listed = bands.get(country);
        if (listed != null && listed.isEmpty()) {
            return new PricingQuote(country, null, false, false, null, VARIANT_BAND, List.of());
        }
        boolean isDefault = listed == null;
        PriceBand band = isDefault ? PriceBand.B1 : listed.get();

        if ("IN".equals(country)) {
            String variant = VARIANT_SUNO_LIKE.equals(requestedVariant) ? VARIANT_SUNO_LIKE : VARIANT_BAND;
            List<BigDecimal> inr = INDIA_INR.get(variant);
            return new PricingQuote(country, band, false, true, "INR", variant, List.of(
                    new PricingQuote.PlanPrice("creator", inr.get(0), true),
                    new PricingQuote.PlanPrice("pro", inr.get(1), true)));
        }

        return new PricingQuote(country, band, isDefault, true, "USD", VARIANT_BAND, List.of(
                new PricingQuote.PlanPrice("creator", usd(BASE_CREATOR_USD, band), false),
                new PricingQuote.PlanPrice("pro", usd(BASE_PRO_USD, band), false)));
    }

    /** Returns an upper-case ISO 3166-1 alpha-2 code, or "XX" when the input is not one. */
    static String normaliseCountry(String code) {
        if (code == null) {
            return "XX";
        }
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return upper.matches("[A-Z]{2}") ? upper : "XX";
    }

    private static BigDecimal usd(BigDecimal base, PriceBand band) {
        return base.multiply(band.factor()).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Optional<PriceBand>> loadBands(String path) {
        Map<String, Optional<PriceBand>> result = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("country,")) {
                    continue;
                }
                String[] parts = line.split(",");
                Optional<PriceBand> band = "none".equals(parts[1])
                        ? Optional.empty()
                        : Optional.of(PriceBand.valueOf(parts[1]));
                if (result.put(parts[0], band) != null) {
                    throw new IllegalStateException("Duplicate country in " + path + ": " + parts[0]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(result);
    }
}
