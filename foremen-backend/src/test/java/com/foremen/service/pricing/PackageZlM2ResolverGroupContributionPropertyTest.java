package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Property-based tests for {@link PackageZlM2Resolver} — Property 9: the package zł/m² price
 * equals the sum over groups of each group's contribution, where each group's contribution is
 * (Σ {@code avgPrice × qtyRef50} for the package's lines) ÷ 50 (FOR-05-04, design §Correctness
 * Properties, §6.6).
 *
 * <p>Unlike {@link PackageZlM2ResolverPropertyTest} (task 9.5, Property 10 — the "typical
 * product never drives the value" checks), this class exercises the resolver's actual
 * arithmetic: for randomly generated groups of {@link PackageZlM2Resolver.Line}s (mixed with
 * noise lines tagged to other package codes), it cross-checks {@code packageZlM2} against an
 * independently recomputed oracle built by manually reducing
 * {@code Σ(avgPrice × qtyRef50) / 50}, rounded per group and then summed — not merely against
 * {@code groupContribution} calls (which would only prove internal self-consistency).
 *
 * <p>No Spring context and no database — the resolver is exercised via {@code new
 * PackageZlM2Resolver()} over in-memory {@link PackageZlM2Resolver.Line} collections.
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

    /** Seeded package codes (FOR-04-10) plus a code that never appears as a target. */
    private static final List<String> SEEDED_CODES = List.of("budget", "norm", "lux");

    // ------------------------------------------------------------------------------------------
    // Property 9a: packageZlM2 equals the sum of groupContribution computed independently for
    // each group (cross-check against the resolver's own per-group method, summed by the test).
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
    void packageZlM2EqualsSumOfIndependentlyComputedGroupContributions(
            @ForAll("assortments") Assortment assortment) {

        BigDecimal total = resolver.packageZlM2(assortment.linesByGroup(), assortment.pkgCode());

        BigDecimal sumOfContributions = BigDecimal.ZERO;
        for (List<PackageZlM2Resolver.Line> groupLines : assortment.linesByGroup().values()) {
            sumOfContributions = sumOfContributions.add(
                    resolver.groupContribution(groupLines, assortment.pkgCode()));
        }
        sumOfContributions = sumOfContributions.setScale(SCALE, RoundingMode.HALF_UP);

        assertThat(total).isEqualByComparingTo(sumOfContributions);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9b: packageZlM2 matches a manually recomputed oracle — Σ(avgPrice × qtyRef50) / 50
    // per group (rounded to 2 decimals), summed across groups — verifying the resolver's actual
    // arithmetic rather than just internal self-consistency.
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
    void packageZlM2MatchesManuallyRecomputedOracle(@ForAll("assortments") Assortment assortment) {
        BigDecimal total = resolver.packageZlM2(assortment.linesByGroup(), assortment.pkgCode());

        BigDecimal oracle = BigDecimal.ZERO;
        for (List<PackageZlM2Resolver.Line> groupLines : assortment.linesByGroup().values()) {
            BigDecimal groupSum = BigDecimal.ZERO;
            for (PackageZlM2Resolver.Line line : groupLines) {
                if (!assortment.pkgCode().equals(line.packageCode())) {
                    continue;
                }
                groupSum = groupSum.add(line.avgPrice().multiply(line.qtyRef50()));
            }
            BigDecimal groupContribution = groupSum
                    .divide(REF_AREA, 10, RoundingMode.HALF_UP)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            oracle = oracle.add(groupContribution);
        }
        oracle = oracle.setScale(SCALE, RoundingMode.HALF_UP);

        assertThat(total).isEqualByComparingTo(oracle);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9c: noise lines tagged to a different packageCode never affect the result for the
    // target package.
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
    void linesForOtherPackagesNeverAffectTheResult(
            @ForAll("assortments") Assortment assortment,
            @ForAll("noisePricesAndQuantities") List<BigDecimal[]> extraNoisePricesAndQuantities) {

        BigDecimal totalWithoutExtraNoise =
                resolver.packageZlM2(assortment.linesByGroup(), assortment.pkgCode());

        // Build extra noise lines explicitly tagged to a code OTHER than assortment.pkgCode() —
        // derived from the actual target so the exclusion always holds, regardless of which
        // package code the assortment generator picked.
        String otherCode = SEEDED_CODES.stream()
                .filter(c -> !c.equals(assortment.pkgCode()))
                .findFirst()
                .orElseThrow();

        // Append extra noise lines (tagged to a code other than the target) into every group.
        Map<Long, List<PackageZlM2Resolver.Line>> withExtraNoise = new LinkedHashMap<>();
        for (Map.Entry<Long, List<PackageZlM2Resolver.Line>> entry : assortment.linesByGroup().entrySet()) {
            List<PackageZlM2Resolver.Line> merged = new ArrayList<>(entry.getValue());
            for (BigDecimal[] priceAndQty : extraNoisePricesAndQuantities) {
                merged.add(new PackageZlM2Resolver.Line(
                        entry.getKey(), otherCode, priceAndQty[0], priceAndQty[1]));
            }
            withExtraNoise.put(entry.getKey(), merged);
        }

        BigDecimal totalWithExtraNoise = resolver.packageZlM2(withExtraNoise, assortment.pkgCode());

        assertThat(totalWithExtraNoise).isEqualByComparingTo(totalWithoutExtraNoise);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9d: empty/null inputs yield zero.
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Test
    void emptyAndNullInputsYieldZero() {
        assertThat(resolver.packageZlM2(Map.of(), "budget")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolver.packageZlM2(null, "budget")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolver.packageZlM2(Map.of(1L, List.of()), "budget")).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(resolver.groupContribution(List.of(), "budget")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolver.groupContribution(null, "budget")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9e: the result is always rounded to exactly 2 decimal places.
    // Validates: Requirements 6.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 9")
    void resultIsAlwaysRoundedToTwoDecimalPlaces(@ForAll("assortments") Assortment assortment) {
        BigDecimal total = resolver.packageZlM2(assortment.linesByGroup(), assortment.pkgCode());

        assertThat(total.scale()).isEqualTo(SCALE);

        for (List<PackageZlM2Resolver.Line> groupLines : assortment.linesByGroup().values()) {
            BigDecimal contribution = resolver.groupContribution(groupLines, assortment.pkgCode());
            assertThat(contribution.scale()).isEqualTo(SCALE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> packageCodes() {
        return Arbitraries.of(SEEDED_CODES);
    }

    private Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000.00"))
                .ofScale(2);
    }

    private Arbitrary<BigDecimal> quantities() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("500.00"))
                .ofScale(2);
    }

    private Arbitrary<Long> groupIds() {
        return Arbitraries.longs().between(1L, 20L);
    }

    /** A single line tagged to the given package code. */
    private Arbitrary<PackageZlM2Resolver.Line> targetLine(String pkgCode, Long groupId) {
        return Combinators.combine(prices(), quantities())
                .as((avgPrice, qtyRef50) -> new PackageZlM2Resolver.Line(groupId, pkgCode, avgPrice, qtyRef50));
    }

    /** A single noise line tagged to a package code other than the target. */
    private Arbitrary<PackageZlM2Resolver.Line> noiseLine(String targetCode, Long groupId) {
        Arbitrary<String> otherCodes = Arbitraries.of(SEEDED_CODES).filter(c -> !c.equals(targetCode));
        return Combinators.combine(otherCodes, prices(), quantities())
                .as((code, avgPrice, qtyRef50) -> new PackageZlM2Resolver.Line(groupId, code, avgPrice, qtyRef50));
    }

    @Provide
    Arbitrary<List<BigDecimal[]>> noisePricesAndQuantities() {
        return Combinators.combine(prices(), quantities())
                .as((price, qty) -> new BigDecimal[] {price, qty})
                .list().ofMinSize(0).ofMaxSize(6);
    }

    /**
     * A randomly generated assortment: a target package code plus a map of group id to a list of
     * {@link PackageZlM2Resolver.Line}s, each group containing a random number of lines tagged to
     * the target package code interleaved with noise lines tagged to other package codes (which
     * must never affect the result).
     */
    private record Assortment(String pkgCode, Map<Long, List<PackageZlM2Resolver.Line>> linesByGroup) {
    }

    @Provide
    Arbitrary<Assortment> assortments() {
        Arbitrary<String> codes = packageCodes();
        Arbitrary<List<Long>> groupIdLists = groupIds().list().ofMinSize(1).ofMaxSize(8).uniqueElements();

        return Combinators.combine(codes, groupIdLists).flatAs((pkgCode, groupIdList) -> {
            List<Arbitrary<Map.Entry<Long, List<PackageZlM2Resolver.Line>>>> groupArbitraries = new ArrayList<>();
            for (Long groupId : groupIdList) {
                Arbitrary<List<PackageZlM2Resolver.Line>> targetLinesForGroup =
                        targetLine(pkgCode, groupId).list().ofMinSize(0).ofMaxSize(6);
                Arbitrary<List<PackageZlM2Resolver.Line>> noiseLinesForGroup =
                        noiseLine(pkgCode, groupId).list().ofMinSize(0).ofMaxSize(6);

                Arbitrary<Map.Entry<Long, List<PackageZlM2Resolver.Line>>> groupEntry =
                        Combinators.combine(targetLinesForGroup, noiseLinesForGroup).as((targetLines, noise) -> {
                            List<PackageZlM2Resolver.Line> merged = new ArrayList<>(targetLines);
                            merged.addAll(noise);
                            return Map.entry(groupId, merged);
                        });
                groupArbitraries.add(groupEntry);
            }

            return Combinators.combine(groupArbitraries).as(entries -> {
                Map<Long, List<PackageZlM2Resolver.Line>> linesByGroup = new LinkedHashMap<>();
                for (Map.Entry<Long, List<PackageZlM2Resolver.Line>> entry : entries) {
                    linesByGroup.put(entry.getKey(), entry.getValue());
                }
                return new Assortment(pkgCode, linesByGroup);
            });
        });
    }
}
