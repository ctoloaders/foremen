package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.service.offer.DiscountResolver.EffectiveDiscount;
import com.foremen.service.offer.OfferTotalsCalculator.OfferTotals;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link OfferTotalsCalculator} — the pure function recomputing an offer's
 * monetary totals from the <b>live-referenced</b> estimate client-facing net prices and the surviving
 * effective discounts (FOR-05-07, design §Property 1).
 *
 * <p>Property 1 states: for all priced estimates and all discount sets, the offer's {@code totalNet}
 * equals the referenced estimate's client-facing final net minus the sum of the effective discounts
 * ({@code totalNet = max(0, estimateTotalNet − Σ EffectiveDiscount(net))}), never negative; with
 * {@code totalVat = round(totalNet × vatRate/100)} and {@code totalGross = totalNet + totalVat},
 * recomputed from the discounted net at the project VAT rate — and derived purely from the passed-in
 * live prices, never from a stored per-line offer copy.
 *
 * <p>The calculator is exercised directly as a pure, total, deterministic function — no persistence —
 * so Property 1 is cheap to run over 100+ iterations.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 1: Offer totals equal live-referenced estimate net
 * minus effective discounts
 *
 * <p><b>Validates: Requirements 1.3, 2.6, 10.3, 10.15, 19.4</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 1: Offer totals equal live-referenced estimate net minus effective discounts")
class OfferTotalsCalculatorPropertyTest {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final OfferTotalsCalculator calculator = new OfferTotalsCalculator();

    /** A generated scenario: the live-referenced estimate line net prices, the surviving effective
     * discounts keyed by line, and the project VAT rate. */
    private record Scenario(Map<Long, BigDecimal> estimateClientPrices,
                            Map<Long, EffectiveDiscount> effectiveDiscounts,
                            BigDecimal vatRate) {
    }

    // ------------------------------------------------------------------------------------------
    // Property 1a: totalNet == max(0, Σ live estimate net − Σ clamped effective discount amounts),
    // computed against an independent oracle that mirrors the calculator's own summation semantics.
    // Validates: Requirements 1.3, 2.6, 10.3, 10.15
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 1: Offer totals equal live-referenced estimate net minus effective discounts")
    void totalNetEqualsLiveEstimateNetMinusEffectiveDiscounts(@ForAll("scenarios") Scenario s) {
        OfferTotals totals =
                calculator.compute(s.estimateClientPrices(), s.effectiveDiscounts(), s.vatRate());

        BigDecimal estimateNet = oracleEstimateNet(s.estimateClientPrices());
        BigDecimal discountSum = oracleDiscountSum(s.effectiveDiscounts());
        BigDecimal expectedNet = estimateNet.subtract(discountSum).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        assertThat(totals.totalNet())
                .as("totalNet = max(0, Σ live estimate net − Σ effective discounts)")
                .isEqualByComparingTo(expectedNet);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1b: totalNet is never negative, regardless of how large the discounts are.
    // Validates: Requirements 10.3 (clamping to >= 0)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 1: Offer totals equal live-referenced estimate net minus effective discounts")
    void totalNetIsNeverNegative(@ForAll("scenarios") Scenario s) {
        OfferTotals totals =
                calculator.compute(s.estimateClientPrices(), s.effectiveDiscounts(), s.vatRate());

        assertThat(totals.totalNet())
                .as("totalNet is clamped to >= 0")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1c: totalVat == round2(totalNet × vatRate/100) and totalGross == totalNet + totalVat,
    // recomputed from the discounted net at the project VAT rate.
    // Validates: Requirements 1.3, 2.6, 10.15
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 1: Offer totals equal live-referenced estimate net minus effective discounts")
    void vatAndGrossAreDerivedFromTheDiscountedNet(@ForAll("scenarios") Scenario s) {
        OfferTotals totals =
                calculator.compute(s.estimateClientPrices(), s.effectiveDiscounts(), s.vatRate());

        BigDecimal rate = s.vatRate() == null || s.vatRate().signum() < 0 ? BigDecimal.ZERO : s.vatRate();
        BigDecimal expectedVat = totals.totalNet().multiply(rate)
                .divide(HUNDRED, 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedGross = totals.totalNet().add(expectedVat).setScale(2, RoundingMode.HALF_UP);

        assertThat(totals.totalVat())
                .as("totalVat = round(totalNet × vatRate/100)")
                .isEqualByComparingTo(expectedVat);
        assertThat(totals.totalGross())
                .as("totalGross = totalNet + totalVat")
                .isEqualByComparingTo(expectedGross);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1d: totals are derived PURELY from the passed-in live prices, never from a stored
    // copy. Two facets:
    //   (1) purity/determinism — the same inputs always yield the same totals, and mutating the input
    //       maps AFTER the call never changes the returned totals (no retained reference);
    //   (2) live-reference tracking — changing a referenced estimate line price changes the offer
    //       totals accordingly (the offer holds no frozen per-line copy that would ignore the change).
    // Validates: Requirements 19.4, 10.15
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 1: Offer totals equal live-referenced estimate net minus effective discounts")
    void totalsAreDerivedPurelyFromTheLivePrices(@ForAll("scenarios") Scenario s) {
        Map<Long, BigDecimal> prices = new HashMap<>(s.estimateClientPrices());
        Map<Long, EffectiveDiscount> discounts = new HashMap<>(s.effectiveDiscounts());

        OfferTotals first = calculator.compute(prices, discounts, s.vatRate());
        OfferTotals second = calculator.compute(prices, discounts, s.vatRate());

        // (1a) deterministic: same inputs -> same outputs.
        assertThat(second.totalNet()).isEqualByComparingTo(first.totalNet());
        assertThat(second.totalVat()).isEqualByComparingTo(first.totalVat());
        assertThat(second.totalGross()).isEqualByComparingTo(first.totalGross());

        // (1b) no retained reference: mutating the input maps after the call must not change the
        // already-returned totals.
        prices.put(-999L, BigDecimal.valueOf(123456));
        discounts.clear();
        assertThat(first.totalNet())
                .as("returned totals are immutable to post-call input mutation")
                .isEqualByComparingTo(oracleEstimateNet(s.estimateClientPrices())
                        .subtract(oracleDiscountSum(s.effectiveDiscounts()))
                        .max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP));

        // (2) live-reference tracking: adding net to a referenced line (with no covering discount)
        // raises the offer net by exactly that amount (rounding aside). This proves the offer reads
        // the live prices rather than a stored per-line copy.
        Map<Long, BigDecimal> raised = new HashMap<>(s.estimateClientPrices());
        long freshLineId = nextFreeLineId(s.estimateClientPrices());
        BigDecimal bump = BigDecimal.valueOf(1000);
        raised.put(freshLineId, bump);
        OfferTotals afterBump = calculator.compute(raised, s.effectiveDiscounts(), s.vatRate());

        BigDecimal baselineNet = oracleEstimateNet(s.estimateClientPrices())
                .subtract(oracleDiscountSum(s.effectiveDiscounts())).max(BigDecimal.ZERO);
        BigDecimal expectedBumpedNet = baselineNet.add(bump).setScale(2, RoundingMode.HALF_UP);
        assertThat(afterBump.totalNet())
                .as("adding live estimate net (no covering discount) tracks through to offer net")
                .isEqualByComparingTo(expectedBumpedNet);
    }

    // ------------------------------------------------------------------------------------------
    // Oracles mirroring the calculator's documented summation semantics.
    // ------------------------------------------------------------------------------------------

    /** Σ of the client-facing line net, treating null/non-positive line prices as zero. */
    private static BigDecimal oracleEstimateNet(Map<Long, BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal price : prices.values()) {
            if (price != null && price.signum() > 0) {
                sum = sum.add(price);
            }
        }
        return sum;
    }

    /**
     * Σ of the effective discount amounts, re-clamping each to {@code [0, netBase]} and treating a
     * null/non-positive netBase as zero (mirrors {@code OfferTotalsCalculator.clampAmount}).
     */
    private static BigDecimal oracleDiscountSum(Map<Long, EffectiveDiscount> discounts) {
        if (discounts == null || discounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (EffectiveDiscount d : discounts.values()) {
            if (d == null) {
                continue;
            }
            BigDecimal amount = d.amount();
            BigDecimal netBase = d.netBase();
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            if (netBase == null || netBase.signum() <= 0) {
                continue;
            }
            sum = sum.add(amount.compareTo(netBase) > 0 ? netBase : amount);
        }
        return sum;
    }

    private static long nextFreeLineId(Map<Long, BigDecimal> prices) {
        long max = 0L;
        for (Long id : prices.keySet()) {
            if (id != null && id > max) {
                max = id;
            }
        }
        return max + 1L;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A scenario over a small set of estimate lines: each line gets a client-facing net price, and a
     * subset carries an effective discount clamped to its line base. Prices/bases are non-negative
     * monetary amounts (2dp); the VAT rate ranges over realistic values plus zero.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Integer> lineCount = Arbitraries.integers().between(0, 8);
        return lineCount.flatMap(this::scenarioWithLines);
    }

    private Arbitrary<Scenario> scenarioWithLines(int lineCount) {
        Arbitrary<BigDecimal> vat = Arbitraries.of(
                BigDecimal.ZERO, BigDecimal.valueOf(5), BigDecimal.valueOf(8),
                BigDecimal.valueOf(23), new BigDecimal("12.50"));

        if (lineCount == 0) {
            return vat.map(rate -> new Scenario(new LinkedHashMap<>(), new LinkedHashMap<>(), rate));
        }

        // For each line: a net price and an optional (surviving) effective discount.
        Arbitrary<List<LineSpec>> lines = lineSpec().list().ofSize(lineCount);
        return Combinators.combine(lines, vat).as((specs, rate) -> {
            Map<Long, BigDecimal> prices = new LinkedHashMap<>();
            Map<Long, EffectiveDiscount> discounts = new LinkedHashMap<>();
            long id = 1L;
            for (LineSpec spec : specs) {
                long lineId = id++;
                prices.put(lineId, spec.net());
                if (spec.discountAmount() != null) {
                    discounts.put(lineId, new EffectiveDiscount(
                            DiscountScope.LINE, spec.kind(), spec.rawValue(), spec.net(), spec.discountAmount()));
                }
            }
            return new Scenario(prices, discounts, rate);
        });
    }

    /** Per-line generated shape: the net base and an optional effective discount within [0, net]. */
    private record LineSpec(BigDecimal net, DiscountKind kind, BigDecimal rawValue, BigDecimal discountAmount) {
    }

    private Arbitrary<LineSpec> lineSpec() {
        Arbitrary<BigDecimal> net = money(0, 100_000);
        return net.flatMap(base -> Arbitraries.integers().between(0, 100).flatMap(pct -> {
            // pct == 0 => no discount on this line; otherwise a percentage of the base, clamped.
            if (pct == 0 || base.signum() <= 0) {
                return Arbitraries.just(new LineSpec(base, null, null, null));
            }
            BigDecimal rawValue = BigDecimal.valueOf(pct);
            BigDecimal amount = base.multiply(rawValue)
                    .divide(HUNDRED, 10, RoundingMode.HALF_UP)
                    .min(base)
                    .setScale(2, RoundingMode.HALF_UP);
            return Arbitraries.just(new LineSpec(base, DiscountKind.PERCENT, rawValue, amount));
        }));
    }

    /** A non-negative monetary amount in {@code [min, max]} with 2 decimal places. */
    private Arbitrary<BigDecimal> money(long min, long max) {
        return Arbitraries.longs().between(min * 100, max * 100)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }
}
