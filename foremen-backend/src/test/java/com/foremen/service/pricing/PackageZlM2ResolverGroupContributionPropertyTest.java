package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link PackageZlM2Resolver} under the FOR-05-04-UI per-price quantity
 * model — Property 9: the package zł/m² for a band equals
 * {@code round2( ( Σ over positions( price × quantity ) ) ÷ 50 )}.
 *
 * <p>The quantity is resolved per position BEFORE it reaches the resolver (the position's per-band
 * override, or the group's {@code referenceQty} when there is no override), so the resolver just
 * sums {@code price × quantity} over a flat list of {@link PackageZlM2Resolver.Contribution}s and
 * divides by the 50 m² reference area. A {@code null} price or quantity contributes 0.
 *
 * <p>No Spring context and no database — the resolver is exercised via {@code new
 * PackageZlM2Resolver()} over in-memory collections.
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 9
 *
 * <p><b>Validates: Requirements 6.3, 6.4</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
class PackageZlM2ResolverGroupContributionPropertyTest {

    private static final BigDecimal REF_AREA = BigDecimal.valueOf(50);
    private static final int SCALE = 2;

    private final PackageZlM2Resolver resolver = new PackageZlM2Resolver();

    // ------------------------------------------------------------------------------------------
    // Property 9a: packageZlM2 matches a manually recomputed oracle — round2( Σ(price × qty) / 50 ).
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
    void packageZlM2MatchesManuallyRecomputedOracle(
            @ForAll("contributionLists") List<PackageZlM2Resolver.Contribution> contributions) {

        BigDecimal total = resolver.packageZlM2(contributions);

        BigDecimal sum = BigDecimal.ZERO;
        for (PackageZlM2Resolver.Contribution c : contributions) {
            if (c.price() == null || c.quantity() == null) {
                continue;
            }
            sum = sum.add(c.price().multiply(c.quantity()));
        }
        BigDecimal oracle = sum.divide(REF_AREA, 10, RoundingMode.HALF_UP).setScale(SCALE, RoundingMode.HALF_UP);

        assertThat(total).isEqualByComparingTo(oracle);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9b: contributions with a null price or null quantity are ignored (contribute 0).
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
    void nullPriceOrQuantityContributionsAreIgnored(
            @ForAll("contributionLists") List<PackageZlM2Resolver.Contribution> contributions) {

        BigDecimal baseline = resolver.packageZlM2(contributions);

        List<PackageZlM2Resolver.Contribution> withNulls = new ArrayList<>(contributions);
        withNulls.add(new PackageZlM2Resolver.Contribution(null, new BigDecimal("5")));
        withNulls.add(new PackageZlM2Resolver.Contribution(new BigDecimal("5"), null));
        withNulls.add(new PackageZlM2Resolver.Contribution(null, null));

        assertThat(resolver.packageZlM2(withNulls)).isEqualByComparingTo(baseline);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9c: the result is always rounded to exactly 2 decimal places.
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
    void resultIsAlwaysRoundedToTwoDecimalPlaces(
            @ForAll("contributionLists") List<PackageZlM2Resolver.Contribution> contributions) {
        assertThat(resolver.packageZlM2(contributions).scale()).isEqualTo(SCALE);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9d: empty/null inputs yield zero.
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Test
    void emptyAndNullInputsYieldZero() {
        assertThat(resolver.packageZlM2(List.of())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolver.packageZlM2(null)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolver.packageZlM2(
                List.of(new PackageZlM2Resolver.Contribution(null, BigDecimal.ONE))))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Concrete worked example (FOR-05-04-UI, per-price qty): two positions, prices 10 and 20 with
    // quantities 3 and 5 => (10*3 + 20*5) / 50 = (30 + 100) / 50 = 2.60.
    // ------------------------------------------------------------------------------------------

    @Test
    void perPositionQuantityWorkedExample() {
        List<PackageZlM2Resolver.Contribution> contributions = List.of(
                new PackageZlM2Resolver.Contribution(new BigDecimal("10"), new BigDecimal("3")),
                new PackageZlM2Resolver.Contribution(new BigDecimal("20"), new BigDecimal("5")));

        assertThat(resolver.packageZlM2(contributions)).isEqualByComparingTo(new BigDecimal("2.60"));
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    private Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000.00"))
                .ofScale(2);
    }

    private Arbitrary<BigDecimal> quantities() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.0001"), new BigDecimal("500.0000"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<List<PackageZlM2Resolver.Contribution>> contributionLists() {
        Arbitrary<PackageZlM2Resolver.Contribution> contribution =
                Combinators.combine(prices(), quantities())
                        .as(PackageZlM2Resolver.Contribution::new);
        return contribution.list().ofMinSize(0).ofMaxSize(30);
    }
}
