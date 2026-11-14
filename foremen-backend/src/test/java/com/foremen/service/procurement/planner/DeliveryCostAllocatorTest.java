package com.foremen.service.procurement.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Unit and property tests for {@link DeliveryCostAllocator} (FOR-11-01 task 2.1).
 *
 * <p><b>Validates: Requirements 4.3</b>
 */
class DeliveryCostAllocatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal sum(List<BigDecimal> xs) {
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void emptyWeightsGiveEmptyList() {
        assertThat(DeliveryCostAllocator.allocate(bd("10.00"), List.of())).isEmpty();
    }

    @Test
    void splitsProportionallyAndSumsToTotal() {
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(bd("100.00"), List.of(bd("1"), bd("1"), bd("1")));

        assertThat(shares).hasSize(3);
        assertThat(sum(shares)).isEqualByComparingTo("100.00");
        // 33.34 / 33.33 / 33.33 : the extra cent goes to the lowest index on equal remainders
        assertThat(shares.get(0)).isEqualByComparingTo("33.34");
        assertThat(shares.get(1)).isEqualByComparingTo("33.33");
        assertThat(shares.get(2)).isEqualByComparingTo("33.33");
    }

    @Test
    void largestRemainderGetsTheExtraCent() {
        // 0.10 over weights 1:2 -> exact 3.33 / 6.67 cents -> floors 3 / 6, extra cent to the larger remainder
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(bd("0.10"), List.of(bd("1"), bd("2")));

        assertThat(shares.get(0)).isEqualByComparingTo("0.03");
        assertThat(shares.get(1)).isEqualByComparingTo("0.07");
    }

    @Test
    void zeroTotalWeightSplitsEvenlyWithRemainderToFirst() {
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(bd("0.05"), List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(shares.get(0)).isEqualByComparingTo("0.02");
        assertThat(shares.get(1)).isEqualByComparingTo("0.02");
        assertThat(shares.get(2)).isEqualByComparingTo("0.01");
        assertThat(sum(shares)).isEqualByComparingTo("0.05");
    }

    @Test
    void zeroWeightPositionGetsNothingWhenOthersHaveWeight() {
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(bd("10.00"), List.of(BigDecimal.ZERO, bd("5")));

        assertThat(shares.get(0)).isEqualByComparingTo("0.00");
        assertThat(shares.get(1)).isEqualByComparingTo("10.00");
    }

    @Test
    void zeroTotalGivesZeroShares() {
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(BigDecimal.ZERO, List.of(bd("3"), bd("4")));

        assertThat(sum(shares)).isEqualByComparingTo("0.00");
    }

    // ---- properties ----

    @Property(tries = 300)
    void sharesSumExactlyToTotalAndAreNonNegative(@ForAll("totals") BigDecimal total,
                                                  @ForAll("weightLists") List<BigDecimal> weights) {
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(total, weights);

        assertThat(shares).hasSameSizeAs(weights);
        assertThat(sum(shares)).isEqualByComparingTo(total);
        assertThat(shares).allSatisfy(s -> {
            assertThat(s.signum()).isGreaterThanOrEqualTo(0);
            assertThat(s.scale()).isEqualTo(2);
        });
    }

    @Property(tries = 300)
    void eachShareIsWithinOneCentOfExactProportion(@ForAll("totals") BigDecimal total,
                                                   @ForAll("positiveWeightLists") List<BigDecimal> weights) {
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(total, weights);
        BigDecimal sumW = sum(weights);
        BigDecimal oneCent = bd("0.01");

        for (int i = 0; i < weights.size(); i++) {
            BigDecimal exact = total.multiply(weights.get(i)).divide(sumW, 10, java.math.RoundingMode.HALF_UP);
            assertThat(shares.get(i).subtract(exact).abs()).isLessThan(oneCent.add(bd("0.0000001")));
        }
    }

    @Provide
    Arbitrary<BigDecimal> totals() {
        return Arbitraries.longs().between(0, 10_000_000L).map(c -> BigDecimal.valueOf(c, 2));
    }

    @Provide
    Arbitrary<List<BigDecimal>> weightLists() {
        return Arbitraries.longs().between(0, 1_000_000L).map(c -> BigDecimal.valueOf(c, 2))
                .list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<BigDecimal>> positiveWeightLists() {
        return Arbitraries.longs().between(1, 1_000_000L).map(c -> BigDecimal.valueOf(c, 2))
                .list().ofMinSize(1).ofMaxSize(20);
    }
}
