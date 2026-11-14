package com.foremen.service.procurement.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.foremen.service.estimate.margins.Margin;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Unit and property tests for {@link PositionMarginCalculator} (FOR-11-01 task 2.3).
 *
 * <p><b>Validates: Requirements 4.2, 4.4</b>
 */
class PositionMarginCalculatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void computesMarginFromPlannedPriceWhenNoActual() {
        // revenue 100*10=1000.00 ; cost 60*10=600.00 ; margin 400.00 = 0.4
        Margin m = PositionMarginCalculator.margin(bd("100"), null, bd("60"), bd("10"), BigDecimal.ZERO);

        assertThat(m.amount()).isEqualByComparingTo("400.00");
        assertThat(m.pct()).isEqualByComparingTo("0.4");
    }

    @Test
    void actualPriceWinsOverPlanned() {
        Margin m = PositionMarginCalculator.margin(bd("100"), bd("70"), bd("60"), bd("10"), null);

        assertThat(m.amount()).isEqualByComparingTo("300.00");
    }

    @Test
    void deliveryShareIsAddedToCost() {
        Margin m = PositionMarginCalculator.margin(bd("100"), null, bd("60"), bd("10"), bd("50.00"));

        assertThat(m.amount()).isEqualByComparingTo("350.00");
        assertThat(m.pct()).isEqualByComparingTo("0.35");
    }

    @Test
    void unavailableWhenSalePriceMissing() {
        Margin m = PositionMarginCalculator.margin(null, bd("1"), bd("1"), bd("10"), BigDecimal.ZERO);

        assertThat(m.amount()).isNull();
        assertThat(m.pct()).isNull();
    }

    @Test
    void unavailableWhenBothPurchasePricesMissing() {
        Margin m = PositionMarginCalculator.margin(bd("100"), null, null, bd("10"), bd("5"));

        assertThat(m.amount()).isNull();
        assertThat(m.pct()).isNull();
    }

    @Test
    void zeroRevenueGivesAmountButNullPercentage() {
        Margin m = PositionMarginCalculator.margin(BigDecimal.ZERO, null, bd("2"), bd("5"), BigDecimal.ZERO);

        assertThat(m.amount()).isEqualByComparingTo("-10.00");
        assertThat(m.pct()).isNull();
    }

    @Test
    void usesRoundedLineNets() {
        // revenue round2(1.005*3)=3.02 ; cost round2(0.333*3)=1.00 ; margin 2.02
        Margin m = PositionMarginCalculator.margin(bd("1.005"), null, bd("0.333"), bd("3"), null);

        assertThat(m.amount()).isEqualByComparingTo("2.02");
    }

    // ---- properties ----

    @Property(tries = 200)
    void amountEqualsRevenueMinusCost(@ForAll("prices") BigDecimal sale,
                                      @ForAll("prices") BigDecimal planned,
                                      @ForAll("quantities") BigDecimal qty,
                                      @ForAll("prices") BigDecimal share) {
        Margin m = PositionMarginCalculator.margin(sale, null, planned, qty, share);

        BigDecimal revenue = PositionMoneyMath.purchaseNet(sale, qty);
        BigDecimal cost = PositionMoneyMath.purchaseNet(planned, qty).add(share);
        assertThat(m.amount()).isEqualByComparingTo(revenue.subtract(cost));
        if (revenue.signum() == 0) {
            assertThat(m.pct()).isNull();
        } else {
            assertThat(m.pct()).isNotNull();
        }
    }

    @Property(tries = 200)
    void actualPriceAlwaysOverridesPlanned(@ForAll("prices") BigDecimal sale,
                                           @ForAll("prices") BigDecimal actual,
                                           @ForAll("prices") BigDecimal planned,
                                           @ForAll("quantities") BigDecimal qty) {
        Margin withBoth = PositionMarginCalculator.margin(sale, actual, planned, qty, null);
        Margin actualOnly = PositionMarginCalculator.margin(sale, actual, null, qty, null);

        assertThat(withBoth).isEqualTo(actualOnly);
    }

    @Provide
    Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, bd("10000")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> quantities() {
        return Arbitraries.bigDecimals().between(bd("0.001"), bd("1000")).ofScale(3);
    }
}
