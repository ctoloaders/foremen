package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.service.offer.DiscountResolver.DiscountInput;
import com.foremen.service.offer.DiscountResolver.EffectiveDiscount;
import com.foremen.service.offer.DiscountResolver.EstimateLineInput;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;

/**
 * Property-based tests for {@link DiscountResolver#resolveEffective(List, List)} — that a resolved
 * {@link EffectiveDiscount} never violates its bounds (FOR-05-07, design §Property 2).
 *
 * <p>For every resolved effective discount the money amount is clamped so a discount never removes
 * more than the line's net base and never goes negative ({@code 0 <= amount <= netBase}); a
 * {@code PERCENT} discount's amount equals {@code round2(netBase × value/100)} clamped to
 * {@code netBase}, and an {@code ABSOLUTE} discount's amount equals {@code value} clamped to
 * {@code netBase}. Discounts are generated across all scopes and kinds, deliberately including
 * {@code PERCENT} values above 100% and {@code ABSOLUTE} values far larger than any line base, so
 * the clamp is the property under test. The resolver is exercised directly as a pure function — no
 * persistence, no Spring context — so the property is cheap over 100+ iterations.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 2: Discount application never violates its bounds
 *
 * <p><b>Validates: Requirements 2.7, 10.1, 10.2</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 2: Discount application never violates its bounds")
class DiscountResolverBoundsPropertyTest {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DiscountResolver resolver = new DiscountResolver();

    // ------------------------------------------------------------------------------------------
    // Property 2: every resolved EffectiveDiscount's amount is within [0, netBase] -- a discount
    // never removes more than the line's net base and never goes negative.
    // Validates: Requirements 2.7, 10.1, 10.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 2: Discount application never violates its bounds")
    void effectiveAmountIsAlwaysWithinItsScopeBase(@ForAll("discounts") @Size(max = 12) List<DiscountInput> discounts,
                                                   @ForAll("lines") @Size(min = 1, max = 8) List<EstimateLineInput> lines) {
        Map<Long, EffectiveDiscount> resolved = resolver.resolveEffective(discounts, lines);

        assertThat(resolved.values()).allSatisfy(eff -> {
            BigDecimal base = eff.netBase();
            assertThat(base)
                    .as("clamped netBase is never negative")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(eff.amount())
                    .as("effective amount never goes negative for %s", eff)
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(eff.amount())
                    .as("effective amount never exceeds the line net base for %s", eff)
                    .isLessThanOrEqualTo(base);
        });
    }

    // ------------------------------------------------------------------------------------------
    // Property 2a: a PERCENT discount's amount equals round2(netBase * value/100) clamped to
    // [0, netBase]; an ABSOLUTE discount's amount equals value clamped to [0, netBase]. This pins
    // down that the bound is enforced by a clamp on the correct raw computation, not by discarding
    // the discount.
    // Validates: Requirements 2.7, 10.1, 10.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 2: Discount application never violates its bounds")
    void effectiveAmountMatchesTheClampedKindComputation(@ForAll("discounts") @Size(max = 12) List<DiscountInput> discounts,
                                                          @ForAll("lines") @Size(min = 1, max = 8) List<EstimateLineInput> lines) {
        Map<Long, EffectiveDiscount> resolved = resolver.resolveEffective(discounts, lines);

        assertThat(resolved.values()).allSatisfy(eff -> {
            BigDecimal expected = expectedClampedAmount(eff.kind(), eff.value(), eff.netBase());
            assertThat(eff.amount())
                    .as("clamped %s amount for value=%s base=%s", eff.kind(), eff.value(), eff.netBase())
                    .isEqualByComparingTo(expected);
        });
    }

    // ------------------------------------------------------------------------------------------
    // Property 2b: even with an oversized ABSOLUTE discount or a >100% PERCENT discount forced onto
    // a line, the effective amount is still clamped to the base and the residual net (base - amount)
    // is never negative -- the offer totalNet can never be driven below zero by a single line.
    // Validates: Requirements 2.7, 10.1, 10.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 2: Discount application never violates its bounds")
    void oversizedDiscountsAreClampedNotOverdrawn(@ForAll("oversizedDiscount") DiscountInput oversized,
                                                  @ForAll("positiveBase") BigDecimal base) {
        // A single LINE-scoped oversized discount aimed at the one line under test.
        long lineId = 1L;
        DiscountInput lineScoped = new DiscountInput(DiscountScope.LINE, lineId, oversized.kind(), oversized.value());
        EstimateLineInput line = new EstimateLineInput(lineId, 10L, base);

        Map<Long, EffectiveDiscount> resolved = resolver.resolveEffective(List.of(lineScoped), List.of(line));

        assertThat(resolved).containsKey(lineId);
        EffectiveDiscount eff = resolved.get(lineId);

        assertThat(eff.amount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(eff.amount()).isLessThanOrEqualTo(eff.netBase());
        assertThat(eff.netBase().subtract(eff.amount()))
                .as("residual net after the discount is never negative")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Oracle: the clamped effective amount the resolver must produce for a surviving discount.
    // Mirrors the design contract (round2(base*value/100) for PERCENT, value for ABSOLUTE), then
    // clamps to [0, base]. Written independently of the resolver's private helper.
    // ------------------------------------------------------------------------------------------
    private static BigDecimal expectedClampedAmount(DiscountKind kind, BigDecimal value, BigDecimal base) {
        if (kind == null || value == null || value.signum() <= 0 || base.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal raw = (kind == DiscountKind.PERCENT)
                ? base.multiply(value).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                : value;
        if (raw.signum() < 0) {
            raw = BigDecimal.ZERO;
        }
        if (raw.compareTo(base) > 0) {
            raw = base;
        }
        return raw.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Arbitrary discounts across every scope and kind. Values span the full range that stresses the
     * clamp: negatives, zero, small fractions, ordinary percents, {@code PERCENT} values well above
     * 100, and {@code ABSOLUTE} values far larger than any generated line base. Occasionally null
     * to exercise the "removes nothing" path.
     */
    @Provide
    Arbitrary<List<DiscountInput>> discounts() {
        Arbitrary<DiscountScope> scopes = Arbitraries.of(DiscountScope.class);
        Arbitrary<DiscountKind> kinds = Arbitraries.of(DiscountKind.class).injectNull(0.05);
        // targetId reuses the small line/category id space so LINE/CATEGORY discounts actually hit lines.
        Arbitrary<Long> targetIds = Arbitraries.longs().between(1L, 20L).injectNull(0.15);
        Arbitrary<BigDecimal> values = discountValues();

        return Combinators.combine(scopes, targetIds, kinds, values)
                .as(DiscountInput::new)
                .list().ofMaxSize(12);
    }

    /**
     * One oversized discount guaranteed to exceed any positive line base: a {@code PERCENT} above
     * 100 or an {@code ABSOLUTE} in the millions.
     */
    @Provide
    Arbitrary<DiscountInput> oversizedDiscount() {
        Arbitrary<DiscountInput> percent = Arbitraries.bigDecimals()
                .between(new BigDecimal("100.01"), new BigDecimal("100000"))
                .map(v -> new DiscountInput(DiscountScope.LINE, 1L, DiscountKind.PERCENT, v));
        Arbitrary<DiscountInput> absolute = Arbitraries.bigDecimals()
                .between(new BigDecimal("1000000"), new BigDecimal("99999999"))
                .map(v -> new DiscountInput(DiscountScope.LINE, 1L, DiscountKind.ABSOLUTE, v));
        return Arbitraries.oneOf(percent, absolute);
    }

    /** A strictly positive net base up to a large amount. */
    @Provide
    Arbitrary<BigDecimal> positiveBase() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("500000"))
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /**
     * Arbitrary lines with a small id/category space (so scoped discounts actually cover lines) and
     * net bases including negatives and null (which the resolver normalizes to zero) as well as
     * ordinary positive amounts.
     */
    @Provide
    Arbitrary<List<EstimateLineInput>> lines() {
        Arbitrary<Long> lineIds = Arbitraries.longs().between(1L, 20L);
        Arbitrary<Long> categoryIds = Arbitraries.longs().between(1L, 8L).injectNull(0.15);
        Arbitrary<BigDecimal> bases = Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000"), new BigDecimal("500000"))
                .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
                .injectNull(0.1);

        Arbitrary<EstimateLineInput> line = Combinators.combine(lineIds, categoryIds, bases)
                .as(EstimateLineInput::new);

        // Deduplicate line ids so the resolver's per-line map has a well-defined entry per line.
        return line.list().ofMinSize(1).ofMaxSize(8).map(DiscountResolverBoundsPropertyTest::dedupeByLineId);
    }

    private static List<EstimateLineInput> dedupeByLineId(List<EstimateLineInput> raw) {
        List<EstimateLineInput> out = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (EstimateLineInput l : raw) {
            if (l.lineId() != null && !seen.contains(l.lineId())) {
                seen.add(l.lineId());
                out.add(l);
            }
        }
        return out.isEmpty() ? List.of(new EstimateLineInput(1L, 1L, new BigDecimal("100.00"))) : out;
    }

    /**
     * Discount magnitudes spanning the clamp-relevant range: negatives and zero (remove nothing),
     * small fractions, and large positives (percents above 100, absolutes above any line base).
     */
    private Arbitrary<BigDecimal> discountValues() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-50"), new BigDecimal("1000000"))
                .map(v -> v.setScale(4, RoundingMode.HALF_UP))
                .injectNull(0.05);
    }
}
