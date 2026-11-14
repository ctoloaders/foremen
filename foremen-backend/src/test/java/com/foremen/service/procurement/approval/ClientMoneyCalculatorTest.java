package com.foremen.service.procurement.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** <b>Validates: Requirements 4.4</b> */
class ClientMoneyCalculatorTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void computesPerLineHalfUpScale2() {
        ClientMoneyCalculator.Money m = ClientMoneyCalculator.calculate(bd("10.00"), bd("23"), bd("3"));
        assertThat(m.unitNet()).isEqualByComparingTo("10.00");
        assertThat(m.vatRatePercent()).isEqualByComparingTo("23.00");
        assertThat(m.unitGross()).isEqualByComparingTo("12.30");
        assertThat(m.lineNet()).isEqualByComparingTo("30.00");
        assertThat(m.lineGross()).isEqualByComparingTo("36.90");
        assertThat(m.lineGross().scale()).isEqualTo(2);
    }

    @Test
    void roundsHalfUp() {
        // 0.05 * 23% = 0.0115 -> unit gross 0.0615 -> 0.06 ; line 0.05*1.5=0.075 -> 0.08
        ClientMoneyCalculator.Money m = ClientMoneyCalculator.calculate(bd("0.05"), bd("23"), bd("1.5"));
        assertThat(m.unitGross()).isEqualByComparingTo("0.06");
        assertThat(m.lineNet()).isEqualByComparingTo("0.08");
        assertThat(m.lineGross()).isEqualByComparingTo("0.10");
    }

    @Test
    void nullVatMeansZeroAndNullInputsGiveNull() {
        ClientMoneyCalculator.Money m = ClientMoneyCalculator.calculate(bd("9.99"), null, bd("2"));
        assertThat(m.unitGross()).isEqualByComparingTo("9.99");
        assertThat(m.lineGross()).isEqualByComparingTo("19.98");
        assertThat(ClientMoneyCalculator.calculate(null, bd("23"), bd("2"))).isNull();
        assertThat(ClientMoneyCalculator.calculate(bd("1"), bd("23"), null)).isNull();
    }

    @Property
    void grossNotBelowNetAndLineWithinOneCentOfUnitTimesQty(@ForAll("prices") BigDecimal unit,
                                                           @ForAll("vats") BigDecimal vat,
                                                           @ForAll("quantities") BigDecimal qty) {
        ClientMoneyCalculator.Money m = ClientMoneyCalculator.calculate(unit, vat, qty);
        assertThat(m.unitGross()).isGreaterThanOrEqualTo(m.unitNet());
        assertThat(m.lineGross()).isGreaterThanOrEqualTo(m.lineNet());
        assertThat(m.lineNet().subtract(unit.multiply(qty)).abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Provide
    Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("100000")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> vats() {
        return Arbitraries.of(new BigDecimal("0"), new BigDecimal("5"), new BigDecimal("8"), new BigDecimal("23"));
    }

    @Provide
    Arbitrary<BigDecimal> quantities() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("10000")).ofScale(3);
    }
}
