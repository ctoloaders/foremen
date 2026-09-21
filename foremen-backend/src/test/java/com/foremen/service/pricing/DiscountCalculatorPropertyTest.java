package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.DiscountKind;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link DiscountCalculator#applyDiscount} — the pure, total,
 * deterministic derivation of a per-package project price's effective unit price from
 * {@code (originalUnitPrice, discountKind, discountValue)} (FOR-05-03, Requirement 5; design §6.3).
 *
 * <p>The calculator is exercised directly as a pure function — no persistence — so Property 8 is
 * cheap to run over 100+ iterations.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 8: Discount neutrality &amp; derivation
 *
 * <p><b>Validates: Requirements 5.2, 5.3</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 8: Discount neutrality & derivation")
class DiscountCalculatorPropertyTest {

    private final DiscountCalculator calculator = new DiscountCalculator();

    // ------------------------------------------------------------------------------------------
    // Property 8a: unpriced stays unpriced regardless of discount fields
    // Validates: Requirement 5.2 (null-passthrough branch, design §6.3)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 8: Discount neutrality & derivation")
    void nullOriginalIsAlwaysUnpriced(@ForAll("anyDiscountKind") DiscountKind kind,
                                       @ForAll("anyDiscountValue") BigDecimal value) {
        BigDecimal actual = calculator.applyDiscount(null, kind, value);

        assertThat(actual).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Property 8b: absent/zero discount is neutral -> effective = original (rounded)
    // Validates: Requirement 5.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 8: Discount neutrality & derivation")
    void absentOrZeroDiscountIsNeutral(@ForAll("originalPrices") BigDecimal original,
                                        @ForAll("neutralDiscounts") NeutralDiscount discount) {
        BigDecimal actual = calculator.applyDiscount(original, discount.kind(), discount.value());

        assertThat(actual).isEqualByComparingTo(original.setScale(2, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------------------------------
    // Property 8c: a real discount is derived per kind, floored at zero, rounded to 2dp, and is
    // never negative
    // Validates: Requirement 5.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 8: Discount neutrality & derivation")
    void realDiscountIsDerivedAndNeverNegative(@ForAll("originalPrices") BigDecimal original,
                                                @ForAll("realDiscountKinds") DiscountKind kind,
                                                @ForAll("realDiscountValues") BigDecimal value) {
        BigDecimal actual = calculator.applyDiscount(original, kind, value);

        BigDecimal expectedRaw = kind == DiscountKind.PERCENT
                ? original.multiply(BigDecimal.ONE.subtract(value.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)))
                : original.subtract(value);
        BigDecimal expected = expectedRaw.signum() < 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : expectedRaw.setScale(2, RoundingMode.HALF_UP);

        assertThat(actual).isEqualByComparingTo(expected);
        assertThat(actual.signum()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------------------------------
    // Property 8d: applyDiscount is a pure function -- same inputs always yield the same output
    // Validates: Requirements 5.2, 5.3 (purity underlying both branches)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 8: Discount neutrality & derivation")
    void isDeterministic(@ForAll("nullableOriginalPrices") BigDecimal original,
                          @ForAll("anyDiscountKind") DiscountKind kind,
                          @ForAll("anyDiscountValue") BigDecimal value) {
        BigDecimal first = calculator.applyDiscount(original, kind, value);
        BigDecimal second = calculator.applyDiscount(original, kind, value);

        if (first == null) {
            assertThat(second).isNull();
        } else {
            assertThat(second).isEqualByComparingTo(first);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    private record NeutralDiscount(DiscountKind kind, BigDecimal value) {
    }

    /** Original unit prices, always present and non-negative. */
    @Provide
    Arbitrary<BigDecimal> originalPrices() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("100000.00"))
                .ofScale(2);
    }

    /** Original unit prices including {@code null} (unpriced), for the determinism property. */
    @Provide
    Arbitrary<BigDecimal> nullableOriginalPrices() {
        return originalPrices().injectNull(0.2);
    }

    /** Either kind, for the null-original property (kind is irrelevant once original is null). */
    @Provide
    Arbitrary<DiscountKind> anyDiscountKind() {
        return Arbitraries.of(DiscountKind.class).injectNull(0.3);
    }

    /** Any discount value including {@code null} and negative-scale edge values. */
    @Provide
    Arbitrary<BigDecimal> anyDiscountValue() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-100.00"), new BigDecimal("1000.00"))
                .ofScale(2)
                .injectNull(0.3);
    }

    /** The three neutral combinations: null kind, null value, or zero value (any kind). */
    @Provide
    Arbitrary<NeutralDiscount> neutralDiscounts() {
        Arbitrary<NeutralDiscount> nullKind = realDiscountValues()
                .map(v -> new NeutralDiscount(null, v));
        Arbitrary<NeutralDiscount> nullValue = Arbitraries.of(DiscountKind.class)
                .map(k -> new NeutralDiscount(k, null));
        Arbitrary<NeutralDiscount> zeroValue = Arbitraries.of(DiscountKind.class)
                .map(k -> new NeutralDiscount(k, BigDecimal.ZERO));
        return Arbitraries.oneOf(nullKind, nullValue, zeroValue);
    }

    /** Real (non-neutral) discount kinds -- both PERCENT and ABSOLUTE. */
    @Provide
    Arbitrary<DiscountKind> realDiscountKinds() {
        return Arbitraries.of(DiscountKind.class);
    }

    /** Real (non-zero, positive) discount values, wide enough to exercise the floor-at-zero branch. */
    @Provide
    Arbitrary<BigDecimal> realDiscountValues() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("500.00"))
                .ofScale(2);
    }
}
