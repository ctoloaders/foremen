package com.foremen.service.procurement.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.service.procurement.planner.PositionMoneyMath.Line;
import com.foremen.service.procurement.planner.PositionMoneyMath.Totals;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Unit and property tests for {@link PositionMoneyMath} (FOR-11-01 task 2.1).
 *
 * <p><b>Validates: Requirements 4.1</b>
 */
class PositionMoneyMathTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void saleLineRoundsNetHalfUpThenVat() {
        // 1.005 * 3 = 3.015 -> 3.02 ; vat 23% of 3.02 = 0.6946 -> 0.69
        Line line = PositionMoneyMath.saleLine(bd("1.005"), bd("3"), bd("23"));

        assertThat(line.net()).isEqualByComparingTo("3.02");
        assertThat(line.vat()).isEqualByComparingTo("0.69");
        assertThat(line.gross()).isEqualByComparingTo("3.71");
        assertThat(line.net().scale()).isEqualTo(2);
    }

    @Test
    void saleLineWithoutVatRateHasNullVatAndGrossEqualsNet() {
        Line line = PositionMoneyMath.saleLine(bd("10.00"), bd("2.5"), null);

        assertThat(line.net()).isEqualByComparingTo("25.00");
        assertThat(line.vat()).isNull();
        assertThat(line.gross()).isEqualByComparingTo("25.00");
    }

    @Test
    void nullPriceOrQuantityGivesNull() {
        assertThat(PositionMoneyMath.saleLine(null, bd("1"), bd("23"))).isNull();
        assertThat(PositionMoneyMath.saleLine(bd("1"), null, bd("23"))).isNull();
        assertThat(PositionMoneyMath.purchaseNet(null, bd("1"))).isNull();
    }

    @Test
    void totalsAreSumOfRoundedLinesNotRoundedSumOfRaw() {
        // three lines of 0.333 * 1 -> 0.33 each (sum 0.99), whereas raw sum 0.999 would round to 1.00
        Line l = PositionMoneyMath.saleLine(bd("0.333"), bd("1"), bd("0"));
        Totals totals = PositionMoneyMath.sum(List.of(l, l, l));

        assertThat(totals.net()).isEqualByComparingTo("0.99");
    }

    @Test
    void vatIsPerRateOnEachLine() {
        Line a = PositionMoneyMath.saleLine(bd("10.00"), bd("1"), bd("23"));
        Line b = PositionMoneyMath.saleLine(bd("10.00"), bd("1"), bd("8"));
        Totals totals = PositionMoneyMath.sum(List.of(a, b));

        assertThat(totals.net()).isEqualByComparingTo("20.00");
        assertThat(totals.vat()).isEqualByComparingTo("3.10");
        assertThat(totals.gross()).isEqualByComparingTo("23.10");
    }

    @Test
    void sumSkipsNullLinesAndHandlesEmpty() {
        Totals empty = PositionMoneyMath.sum(List.of());
        assertThat(empty.net()).isEqualByComparingTo("0.00");

        Line l = PositionMoneyMath.saleLine(bd("5"), bd("1"), null);
        java.util.ArrayList<Line> lines = new java.util.ArrayList<>();
        lines.add(null);
        lines.add(l);
        Totals t = PositionMoneyMath.sum(lines);
        assertThat(t.net()).isEqualByComparingTo("5.00");
        assertThat(t.vat()).isEqualByComparingTo("0.00");
        assertThat(t.gross()).isEqualByComparingTo("5.00");
    }

    // ---- properties ----

    @Property(tries = 200)
    void totalsEqualSumOfLineComponents(@ForAll("lineLists") List<Line> lines) {
        Totals t = PositionMoneyMath.sum(lines);

        BigDecimal net = lines.stream().map(Line::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vat = lines.stream().map(Line::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gross = lines.stream().map(Line::gross).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(t.net()).isEqualByComparingTo(net);
        assertThat(t.vat()).isEqualByComparingTo(vat);
        assertThat(t.gross()).isEqualByComparingTo(gross);
        assertThat(t.gross()).isEqualByComparingTo(t.net().add(t.vat()));
    }

    @Property(tries = 200)
    void nonNegativeInputsGiveNonNegativeScale2Lines(@ForAll("unitPrices") BigDecimal price,
                                                     @ForAll("quantities") BigDecimal qty,
                                                     @ForAll("rates") BigDecimal rate) {
        Line line = PositionMoneyMath.saleLine(price, qty, rate);

        assertThat(line.net().signum()).isGreaterThanOrEqualTo(0);
        assertThat(line.vat().signum()).isGreaterThanOrEqualTo(0);
        assertThat(line.gross()).isGreaterThanOrEqualTo(line.net());
        assertThat(line.net().scale()).isEqualTo(2);
        assertThat(line.vat().scale()).isEqualTo(2);
        assertThat(line.gross()).isEqualByComparingTo(line.net().add(line.vat()));
    }

    @Provide
    Arbitrary<BigDecimal> unitPrices() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, bd("100000")).ofScale(3);
    }

    @Provide
    Arbitrary<BigDecimal> quantities() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, bd("10000")).ofScale(3);
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.of(bd("0"), bd("5"), bd("8"), bd("23"));
    }

    @Provide
    Arbitrary<List<Line>> lineLists() {
        Arbitrary<Line> line = Arbitraries.bigDecimals().between(BigDecimal.ZERO, bd("1000")).ofScale(3)
                .flatMap(p -> quantities().flatMap(q -> rates().map(r -> PositionMoneyMath.saleLine(p, q, r))));
        return line.list().ofMaxSize(30);
    }
}
