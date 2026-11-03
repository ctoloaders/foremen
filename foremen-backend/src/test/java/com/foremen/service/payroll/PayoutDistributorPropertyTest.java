package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.payroll.PayoutDistributor.Allocation;
import com.foremen.service.payroll.PayoutDistributor.Distribution;
import com.foremen.service.payroll.PayoutDistributor.PayableLine;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;

/**
 * FOR-07-06 (task 2.2; design §Correctness properties, Property 3 "Distribution conservation") —
 * property-based tests for the pure {@link PayoutDistributor}.
 *
 * <p>For any amount {@code D >= 0} and any ordered list of lines with remainders {@code r_i >= 0}:
 * {@code Σ allocations = min(D, Σ r)}; every allocation {@code ≤} its remainder and {@code > 0};
 * allocations are a prefix of the positive-remainder lines in order; at most one (the last) line
 * is partial; {@code surplus = max(0, D − Σ r)}; all amounts are scale 2.
 *
 * <p><b>Validates: Requirements 5.3, 5.4, 5.5</b>
 */
@Tag("Feature: FOR-07-06-company-payroll, Property 3: distribution conservation")
class PayoutDistributorPropertyTest {

    private static final int SCALE = 2;

    // =============================================================================================
    // Generators.
    // =============================================================================================

    /** A cent amount in [0, 10 000.00], biased to include zero-remainder lines. */
    private static Arbitrary<BigDecimal> money() {
        return Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(BigDecimal.ZERO.setScale(SCALE))),
                Tuple.of(6, Arbitraries.longs().between(1, 1_000_000)
                        .map(c -> BigDecimal.valueOf(c, SCALE))));
    }

    @Provide
    Arbitrary<List<PayableLine>> lines() {
        return money().list().ofMaxSize(12).map(rs -> {
            List<PayableLine> out = new ArrayList<>();
            for (int i = 0; i < rs.size(); i++) {
                out.add(new PayableLine((long) (i + 1), rs.get(i)));
            }
            return out;
        });
    }

    /** {@code D} in [0, 150 000.00] — covers exhausted, exact and surplus cases. */
    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.oneOf(
                Arbitraries.just(BigDecimal.ZERO.setScale(SCALE)),
                Arbitraries.longs().between(1, 15_000_000).map(c -> BigDecimal.valueOf(c, SCALE)));
    }

    // =============================================================================================
    // Property 3.
    // =============================================================================================

    @Property(tries = 300)
    void conservationAndSurplus(@ForAll("amounts") BigDecimal d,
                                @ForAll("lines") List<PayableLine> lines) {
        Distribution dist = PayoutDistributor.distribute(d, lines);
        BigDecimal sumR = sumRemainders(lines);

        assertThat(dist.allocatedTotal()).isEqualByComparingTo(d.min(sumR));
        assertThat(dist.surplus()).isEqualByComparingTo(d.subtract(sumR).max(BigDecimal.ZERO));
        assertThat(dist.allocatedTotal().add(dist.surplus())).isEqualByComparingTo(d);
        assertThat(dist.surplus().scale()).isEqualTo(SCALE);
        assertThat(dist.surplus().signum()).isGreaterThanOrEqualTo(0);
    }

    @Property(tries = 300)
    void perLineCapAndPositive(@ForAll("amounts") BigDecimal d,
                               @ForAll("lines") List<PayableLine> lines) {
        Map<Long, BigDecimal> remainders = lines.stream()
                .collect(Collectors.toMap(PayableLine::lineId, PayableLine::remainder));
        for (Allocation a : PayoutDistributor.distribute(d, lines).allocations()) {
            assertThat(a.amount().signum()).isPositive();
            assertThat(a.amount().scale()).isEqualTo(SCALE);
            assertThat(a.amount()).isLessThanOrEqualTo(remainders.get(a.lineId()));
        }
    }

    @Property(tries = 300)
    void allocationsArePrefixOfOrder(@ForAll("amounts") BigDecimal d,
                                     @ForAll("lines") List<PayableLine> lines) {
        List<Long> positiveOrder = lines.stream()
                .filter(l -> l.remainder().signum() > 0)
                .map(PayableLine::lineId)
                .toList();
        List<Long> allocated = PayoutDistributor.distribute(d, lines).allocations().stream()
                .map(Allocation::lineId)
                .toList();

        assertThat(allocated).isEqualTo(positiveOrder.subList(0, allocated.size()));
    }

    @Property(tries = 300)
    void atMostOnePartialLineAndItIsTheLast(@ForAll("amounts") BigDecimal d,
                                            @ForAll("lines") List<PayableLine> lines) {
        Map<Long, BigDecimal> remainders = lines.stream()
                .collect(Collectors.toMap(PayableLine::lineId, PayableLine::remainder));
        List<Allocation> allocations = PayoutDistributor.distribute(d, lines).allocations();

        long partial = allocations.stream()
                .filter(a -> a.amount().compareTo(remainders.get(a.lineId())) < 0)
                .count();
        assertThat(partial).isLessThanOrEqualTo(1);
        for (int i = 0; i < allocations.size() - 1; i++) {
            Allocation a = allocations.get(i);
            assertThat(a.amount()).isEqualByComparingTo(remainders.get(a.lineId()));
        }
    }

    // =============================================================================================
    // Examples / edge cases.
    // =============================================================================================

    @Example
    void partialLastLineThenStop() {
        Distribution dist = PayoutDistributor.distribute(new BigDecimal("150.00"), List.of(
                new PayableLine(1L, new BigDecimal("100.00")),
                new PayableLine(2L, new BigDecimal("80.00")),
                new PayableLine(3L, new BigDecimal("40.00"))));

        assertThat(dist.allocations()).containsExactly(
                new Allocation(1L, new BigDecimal("100.00")),
                new Allocation(2L, new BigDecimal("50.00")));
        assertThat(dist.surplus()).isEqualByComparingTo("0");
    }

    @Example
    void surplusStaysUnallocated() {
        Distribution dist = PayoutDistributor.distribute(new BigDecimal("500"), List.of(
                new PayableLine(1L, new BigDecimal("100.00")),
                new PayableLine(2L, BigDecimal.ZERO)));

        assertThat(dist.allocations()).containsExactly(new Allocation(1L, new BigDecimal("100.00")));
        assertThat(dist.surplus()).isEqualTo(new BigDecimal("400.00"));
    }

    @Example
    void nullAndNegativeInputsAreZero() {
        assertThat(PayoutDistributor.distribute(null, List.of(new PayableLine(1L, BigDecimal.TEN)))
                .allocations()).isEmpty();
        Distribution neg = PayoutDistributor.distribute(new BigDecimal("-5"), null);
        assertThat(neg.allocations()).isEmpty();
        assertThat(neg.surplus()).isEqualTo(new BigDecimal("0.00"));
        Distribution rounded = PayoutDistributor.distribute(new BigDecimal("10.005"),
                List.of(new PayableLine(1L, new BigDecimal("-3"))));
        assertThat(rounded.allocations()).isEmpty();
        assertThat(rounded.surplus()).isEqualTo(new BigDecimal("10.01"));
        assertThat(rounded.surplus().setScale(SCALE, RoundingMode.HALF_UP)).isEqualTo(rounded.surplus());
    }

    private static BigDecimal sumRemainders(List<PayableLine> lines) {
        return lines.stream().map(PayableLine::remainder)
                .reduce(BigDecimal.ZERO.setScale(SCALE), BigDecimal::add);
    }
}
