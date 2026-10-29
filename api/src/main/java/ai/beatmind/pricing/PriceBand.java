package ai.beatmind.pricing;

import java.math.BigDecimal;

/** Purchasing-power price bands (DEC-013). Factors are hypotheses under test in EXP-02. */
public enum PriceBand {
    B1("1.0"),
    B2("0.6"),
    B3("0.35"),
    B4("0.2"),
    B5("0.12");

    private final BigDecimal factor;

    PriceBand(String factor) {
        this.factor = new BigDecimal(factor);
    }

    public BigDecimal factor() {
        return factor;
    }
}
