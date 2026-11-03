package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-07-06 (task 2.5; design §Correctness properties, Property 10 "Line status") — property-based
 * tests for the pure {@link LinePaymentStatus} and {@link ReportPaymentStatus}.
 *
 * <p>{@code UNPAID ⇔ paid = 0}, {@code PAID ⇔ paid ≥ cost}, {@code PARTIAL} otherwise; null cost ⇒
 * {@code UNAVAILABLE}. Report: {@code PAID} if all available lines are paid, {@code UNPAID} if none
 * has a paid amount, otherwise {@code PARTIAL}; {@code UNAVAILABLE} if no line has an available cost.
 *
 * <p><b>Validates: Requirements 12.1</b>
 */
@Tag("Feature: FOR-07-06-company-payroll, Property 10: line status")
class LinePaymentStatusPropertyTest {

    /** Money with 2 decimals in [0, 1000.00]; includes 0 so the boundary is exercised. */
    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(0, 100_000)
                .edgeCases(c -> c.add(0L).add(1L))
                .map(c -> BigDecimal.valueOf(c, 2));
    }

    @Provide
    Arbitrary<BigDecimal> positiveCost() {
        return Arbitraries.longs().between(1, 100_000).map(c -> BigDecimal.valueOf(c, 2));
    }

    @Provide
    Arbitrary<List<LinePaymentStatus>> statuses() {
        return Arbitraries.of(LinePaymentStatus.class).injectNull(0.05).list().ofMaxSize(10);
    }

    @Property
    void nullCostIsUnavailable(@ForAll("money") BigDecimal paid) {
        assertThat(LinePaymentStatus.of(null, paid)).isEqualTo(LinePaymentStatus.UNAVAILABLE);
        assertThat(LinePaymentStatus.of(null, null)).isEqualTo(LinePaymentStatus.UNAVAILABLE);
    }

    @Property
    void lineStatusBiconditionals(@ForAll("positiveCost") BigDecimal cost, @ForAll("money") BigDecimal paid) {
        LinePaymentStatus s = LinePaymentStatus.of(cost, paid);

        assertThat(s).isNotEqualTo(LinePaymentStatus.UNAVAILABLE);
        assertThat(s == LinePaymentStatus.UNPAID).isEqualTo(paid.signum() == 0);
        assertThat(s == LinePaymentStatus.PAID).isEqualTo(paid.compareTo(cost) >= 0);
        assertThat(s == LinePaymentStatus.PARTIAL)
                .isEqualTo(paid.signum() > 0 && paid.compareTo(cost) < 0);
    }

    @Property
    void scaleIsIrrelevant(@ForAll("positiveCost") BigDecimal cost, @ForAll("money") BigDecimal paid) {
        assertThat(LinePaymentStatus.of(cost.setScale(4), paid.setScale(6)))
                .isEqualTo(LinePaymentStatus.of(cost, paid));
    }

    @Property
    void reportAggregate(@ForAll("statuses") List<LinePaymentStatus> lines) {
        List<LinePaymentStatus> available = new ArrayList<>();
        for (LinePaymentStatus l : lines) {
            if (l != null && l != LinePaymentStatus.UNAVAILABLE) {
                available.add(l);
            }
        }
        LinePaymentStatus expected;
        if (available.isEmpty()) {
            expected = LinePaymentStatus.UNAVAILABLE;
        } else if (available.stream().allMatch(l -> l == LinePaymentStatus.PAID)) {
            expected = LinePaymentStatus.PAID;
        } else if (available.stream().allMatch(l -> l == LinePaymentStatus.UNPAID)) {
            expected = LinePaymentStatus.UNPAID;
        } else {
            expected = LinePaymentStatus.PARTIAL;
        }
        assertThat(ReportPaymentStatus.of(lines)).isEqualTo(expected);
    }

    @Example
    void examples() {
        BigDecimal cost = new BigDecimal("100.00");
        assertThat(LinePaymentStatus.of(cost, null)).isEqualTo(LinePaymentStatus.UNPAID);
        assertThat(LinePaymentStatus.of(cost, BigDecimal.ZERO)).isEqualTo(LinePaymentStatus.UNPAID);
        assertThat(LinePaymentStatus.of(cost, new BigDecimal("40"))).isEqualTo(LinePaymentStatus.PARTIAL);
        assertThat(LinePaymentStatus.of(cost, new BigDecimal("100"))).isEqualTo(LinePaymentStatus.PAID);
        assertThat(LinePaymentStatus.of(cost, new BigDecimal("120.50"))).isEqualTo(LinePaymentStatus.PAID);
        // Zero-cost line with nothing paid: the "paid = 0" rule takes precedence.
        assertThat(LinePaymentStatus.of(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(LinePaymentStatus.UNPAID);

        assertThat(ReportPaymentStatus.of(null)).isEqualTo(LinePaymentStatus.UNAVAILABLE);
        assertThat(ReportPaymentStatus.of(List.of())).isEqualTo(LinePaymentStatus.UNAVAILABLE);
        assertThat(ReportPaymentStatus.of(List.of(LinePaymentStatus.UNAVAILABLE)))
                .isEqualTo(LinePaymentStatus.UNAVAILABLE);
        assertThat(ReportPaymentStatus.of(List.of(LinePaymentStatus.PAID, LinePaymentStatus.UNAVAILABLE)))
                .isEqualTo(LinePaymentStatus.PAID);
        assertThat(ReportPaymentStatus.of(List.of(LinePaymentStatus.UNPAID, LinePaymentStatus.UNAVAILABLE)))
                .isEqualTo(LinePaymentStatus.UNPAID);
        assertThat(ReportPaymentStatus.of(List.of(LinePaymentStatus.UNPAID, LinePaymentStatus.PAID)))
                .isEqualTo(LinePaymentStatus.PARTIAL);
        assertThat(ReportPaymentStatus.of(List.of(LinePaymentStatus.PARTIAL)))
                .isEqualTo(LinePaymentStatus.PARTIAL);
    }
}
