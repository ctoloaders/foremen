package com.foremen.service.estimate.margins;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.WithNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link MarginCostService} — the pure cost core (FOR-05-06, design
 * §"Correctness Properties"). The service holds no state and needs no Spring context, so the
 * properties are exercised directly over in-memory {@link BigDecimal} / {@link WorkerTypeTier}
 * values.
 *
 * <p>Covers the cost-core portion of the design properties:
 * <ul>
 *   <li><b>Property 1</b> — base cost rounds to the nearest 0.5 zł (HALF_UP); null/zero offer ⇒ null.</li>
 *   <li><b>Property 2</b> — {@code tierCost = baseCost × (1 + uplift)}, base tier uplift 0, monotonic
 *       non-decreasing in uplift.</li>
 *   <li><b>Property 4</b> — labour margin {@code amount = offer − tierCost}; across tiers the min
 *       labour margin sits at the max-cost tier and the max at the min-cost (base) tier.</li>
 *   <li><b>Property 8</b> — unavailable propagation: null/zero offer ⇒ base null ⇒ tier null ⇒
 *       labour margin UNAVAILABLE; null material inputs ⇒ material margin UNAVAILABLE.</li>
 * </ul>
 *
 * <p>Feature: FOR-05-06-packages-margins, cost core
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.4, 2.5, 4.6</b>
 */
@Tag("Feature: FOR-05-06-packages-margins, cost core")
class MarginCostServicePropertyTest {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal BASE_SHARE = new BigDecimal("0.40");

    // ------------------------------------------------------------------------------------------
    // Property 1 — Base cost rounding to 0.5 (HALF_UP), null/zero offer ⇒ null.
    // Validates: Requirements 2.1, 2.4, 2.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-06-packages-margins, Property 1")
    void baseCostIsAlwaysAMultipleOfHalfAndMatchesOracle(@ForAll("positiveOffer") BigDecimal offer) {
        BigDecimal baseCost = MarginCostService.baseCost(offer);

        // Not unavailable for a positive offer.
        assertThat(baseCost).isNotNull();

        // Always a multiple of 0.5: dividing by 0.5 yields an integer.
        BigDecimal steps = baseCost.divide(HALF, 0, RoundingMode.UNNECESSARY);
        assertThat(baseCost).isEqualByComparingTo(steps.multiply(HALF));

        // Oracle: round(0.40 × offer to nearest 0.5, HALF_UP), independently recomputed.
        BigDecimal raw = offer.multiply(BASE_SHARE);
        BigDecimal expected = raw.divide(HALF, 0, RoundingMode.HALF_UP).multiply(HALF);
        assertThat(baseCost).isEqualByComparingTo(expected);

        // The rounded base cost is within half a step of the raw product.
        assertThat(baseCost.subtract(raw).abs()).isLessThanOrEqualTo(HALF);
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-06-packages-margins, Property 1")
    void baseCostIsNullForNullOrZeroOffer(@ForAll("zeroOrNullOffer") BigDecimal offer) {
        assertThat(MarginCostService.baseCost(offer)).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Property 2 — tierCost = baseCost × (1 + uplift); base tier uplift 0; monotonic in uplift.
    // Validates: Requirement 2.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-06-packages-margins, Property 2")
    void baseTierCostEqualsBaseCost(@ForAll("positiveOffer") BigDecimal offer) {
        BigDecimal baseCost = MarginCostService.baseCost(offer);
        WorkerTypeTier baseTier = WorkerTypeTier.base(BASE_SHARE);

        assertThat(MarginCostService.tierCost(baseCost, baseTier)).isEqualByComparingTo(baseCost);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-05-06-packages-margins, Property 2")
    void nonBaseTierCostEqualsBaseTimesOnePlusUplift(
            @ForAll("positiveOffer") BigDecimal offer, @ForAll("uplift") BigDecimal uplift) {
        BigDecimal baseCost = MarginCostService.baseCost(offer);
        WorkerTypeTier tier = WorkerTypeTier.uplift(uplift);

        BigDecimal expected = baseCost.multiply(BigDecimal.ONE.add(uplift));
        assertThat(MarginCostService.tierCost(baseCost, tier)).isEqualByComparingTo(expected);

        // Non-negative uplift ⇒ tier cost is never cheaper than the base cost.
        assertThat(MarginCostService.tierCost(baseCost, tier)).isGreaterThanOrEqualTo(baseCost);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-05-06-packages-margins, Property 2")
    void tierCostIsMonotonicNonDecreasingInUplift(
            @ForAll("positiveOffer") BigDecimal offer,
            @ForAll("uplift") BigDecimal upliftA,
            @ForAll("uplift") BigDecimal upliftB) {
        BigDecimal baseCost = MarginCostService.baseCost(offer);
        BigDecimal lower = upliftA.min(upliftB);
        BigDecimal higher = upliftA.max(upliftB);

        BigDecimal costLower = MarginCostService.tierCost(baseCost, WorkerTypeTier.uplift(lower));
        BigDecimal costHigher = MarginCostService.tierCost(baseCost, WorkerTypeTier.uplift(higher));

        assertThat(costHigher).isGreaterThanOrEqualTo(costLower);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4 (cost-core portion) — labour margin = offer − tierCost; the min labour margin sits
    // at the max-cost tier, the max labour margin at the min-cost (base) tier. Amount ordering is the
    // inverse of the cost ordering.
    // Validates: Requirement 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-06-packages-margins, Property 4")
    void labourMarginEqualsOfferMinusTierCost(
            @ForAll("positiveOffer") BigDecimal offer, @ForAll("uplift") BigDecimal uplift) {
        BigDecimal baseCost = MarginCostService.baseCost(offer);
        BigDecimal tierCost = MarginCostService.tierCost(baseCost, WorkerTypeTier.uplift(uplift));

        Margin margin = MarginCostService.labourMargin(offer, tierCost);

        assertThat(margin.amount()).isEqualByComparingTo(offer.subtract(tierCost));
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-05-06-packages-margins, Property 4")
    void minLabourMarginAtMaxCostTierAndMaxAtBaseTier(
            @ForAll("positiveOffer") BigDecimal offer, @ForAll("upliftSet") List<BigDecimal> uplifts) {
        BigDecimal baseCost = MarginCostService.baseCost(offer);

        // The tier set always includes the base tier (uplift 0 ⇒ min cost) plus the generated uplifts.
        List<WorkerTypeTier> tiers = new ArrayList<>();
        tiers.add(WorkerTypeTier.base(BASE_SHARE));
        for (BigDecimal u : uplifts) {
            tiers.add(WorkerTypeTier.uplift(u));
        }

        List<BigDecimal> costs = new ArrayList<>();
        List<BigDecimal> margins = new ArrayList<>();
        for (WorkerTypeTier tier : tiers) {
            BigDecimal cost = MarginCostService.tierCost(baseCost, tier);
            costs.add(cost);
            margins.add(MarginCostService.labourMargin(offer, cost).amount());
        }

        BigDecimal maxCost = costs.stream().max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal minCost = costs.stream().min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal minMargin = margins.stream().min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal maxMargin = margins.stream().max(Comparator.naturalOrder()).orElseThrow();

        // Min labour margin corresponds to the max-cost tier; max labour margin to the min-cost tier.
        assertThat(minMargin).isEqualByComparingTo(offer.subtract(maxCost));
        assertThat(maxMargin).isEqualByComparingTo(offer.subtract(minCost));

        // The base tier (uplift 0) is the min-cost tier, hence carries the max labour margin.
        assertThat(minCost).isEqualByComparingTo(baseCost);
    }

    // ------------------------------------------------------------------------------------------
    // Property 8 — unavailable propagation. Null/zero offer ⇒ base null ⇒ tier null ⇒ labour margin
    // UNAVAILABLE; null material inputs ⇒ material margin UNAVAILABLE.
    // Validates: Requirements 2.4, 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-06-packages-margins, Property 8")
    void unavailableOfferPropagatesThroughBaseTierAndLabourMargin(
            @ForAll("zeroOrNullOffer") BigDecimal offer, @ForAll("upliftOrBaseTier") WorkerTypeTier tier) {
        BigDecimal baseCost = MarginCostService.baseCost(offer);
        assertThat(baseCost).isNull();

        BigDecimal tierCost = MarginCostService.tierCost(baseCost, tier);
        assertThat(tierCost).isNull();

        Margin labour = MarginCostService.labourMargin(offer, tierCost);
        assertThat(labour).isEqualTo(Margin.UNAVAILABLE);
        assertThat(labour.amount()).isNull();
        assertThat(labour.pct()).isNull();
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-06-packages-margins, Property 8")
    void nullMaterialInputYieldsUnavailableMaterialMargin(
            @ForAll @WithNull(0.5) BigDecimal retail, @ForAll @WithNull(0.5) BigDecimal cost) {
        // The property targets the case where at least one input is null.
        if (retail != null && cost != null) {
            return;
        }
        Margin material = MarginCostService.materialMargin(retail, cost);
        assertThat(material).isEqualTo(Margin.UNAVAILABLE);
        assertThat(material.amount()).isNull();
        assertThat(material.pct()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A strictly positive offer price, up to ~99,999.99 zł, at 2-decimal scale. */
    @Provide
    Arbitrary<BigDecimal> positiveOffer() {
        return Arbitraries.longs().between(1, 9_999_999)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    /** A null or zero offer (the unavailable branch). */
    @Provide
    Arbitrary<BigDecimal> zeroOrNullOffer() {
        return Arbitraries.of(
                (BigDecimal) null,
                BigDecimal.ZERO,
                new BigDecimal("0.00"),
                BigDecimal.valueOf(0, 2));
    }

    /** A non-negative uplift-on-base fraction, 0.0000 .. 2.0000. */
    @Provide
    Arbitrary<BigDecimal> uplift() {
        return Arbitraries.longs().between(0, 20_000)
                .map(bp -> BigDecimal.valueOf(bp, 4));
    }

    /** A set of 1..6 non-negative uplift fractions (distinct not required). */
    @Provide
    Arbitrary<List<BigDecimal>> upliftSet() {
        return uplift().list().ofMinSize(1).ofMaxSize(6);
    }

    /** Either the base tier (0.40 share) or a non-base uplift tier — for null-propagation coverage. */
    @Provide
    Arbitrary<WorkerTypeTier> upliftOrBaseTier() {
        Arbitrary<WorkerTypeTier> base = Arbitraries.just(WorkerTypeTier.base(BASE_SHARE));
        Arbitrary<WorkerTypeTier> nonBase = uplift().map(WorkerTypeTier::uplift);
        return Arbitraries.oneOf(base, nonBase);
    }
}
