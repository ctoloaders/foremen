package com.foremen.service.offer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.service.offer.DiscountResolver.EffectiveDiscount;

/**
 * Recomputes an offer's monetary totals from the <b>live-referenced</b> estimate client-facing final
 * prices and the surviving effective discounts (FOR-05-07, Requirements 1.3, 2.6, 2.7, 10.1, 10.2,
 * 10.3, 10.15, 19.4).
 *
 * <p>The offer never stores its own copy of the per-line estimate prices: totals are always derived
 * from the prices referenced through the {@code Offer.estimate} FK and the offer's own discounts on
 * top (Live_Referenced_Pricing, Requirement 19). This calculator makes that relationship explicit —
 * it takes the estimate's client-facing net per line and the {@link EffectiveDiscount}s already
 * resolved by {@link DiscountResolver} and produces {@code totalNet = estimateTotalNet − Σ
 * EffectiveDiscount(net)}, recomputing VAT and gross from the discounted net.
 *
 * <p>It is a pure, total, deterministic function of its inputs — no I/O, no state — so it is
 * exercised directly by property-based tests without persistence, mirroring the repo-wide stateless
 * {@code @Component} convention ({@link DiscountResolver}, {@code DiscountCalculator}). Two safety
 * invariants are enforced regardless of the inputs:
 *
 * <ul>
 *   <li>{@code totalNet >= 0} — the summed discounts can never drive the net below zero
 *       (Requirement 10.1);</li>
 *   <li>no single effective discount removes more than its own net base — each discount amount is
 *       re-clamped to {@code [0, netBase]} here as a defensive backstop to the clamping
 *       {@link DiscountResolver} already applies (Requirements 2.7, 10.2).</li>
 * </ul>
 */
@Component
public class OfferTotalsCalculator {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * The recomputed offer totals, each rounded to 2 decimals.
     *
     * @param totalNet   the discounted net: the estimate client-facing net minus the summed
     *                   effective discounts, clamped to {@code >= 0}
     * @param totalVat   VAT on the discounted net at the project VAT rate
     * @param totalGross {@code totalNet + totalVat}
     */
    public record OfferTotals(BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross) {
    }

    /**
     * Recomputes the offer totals from the live-referenced estimate client-facing net prices and the
     * surviving effective discounts.
     *
     * <p>The estimate net is the sum of {@code estimateClientPrices} (the client-facing final net of
     * each estimate line, referenced live — never a stored offer copy, Requirement 19.4). Each entry
     * of {@code effectiveDiscounts} contributes its {@link EffectiveDiscount#amount()}, re-clamped to
     * {@code [0, netBase]} so no single discount exceeds its base (Requirements 2.7 / 10.2). The
     * resulting {@code totalNet} is clamped to {@code >= 0} (Requirement 10.1), and VAT/gross are
     * recomputed from that discounted net at {@code vatRate} (Requirements 1.3 / 2.6 / 10.3 / 10.15).
     *
     * @param estimateClientPrices the estimate's client-facing final net keyed by estimate-line id; a
     *                              {@code null} or empty map is treated as a zero estimate net, and
     *                              individual {@code null} / negative line prices are treated as zero
     * @param effectiveDiscounts   the surviving effective discount per line (as resolved by
     *                              {@link DiscountResolver#resolveEffective}); a {@code null} or empty
     *                              map applies no discount
     * @param vatRate              the project VAT rate as a percentage (e.g. {@code 23} for 23%);
     *                              treated as zero when {@code null} or negative
     * @return the recomputed {@link OfferTotals}, each component rounded to 2 decimals
     */
    public OfferTotals compute(Map<Long, BigDecimal> estimateClientPrices,
                               Map<Long, EffectiveDiscount> effectiveDiscounts,
                               BigDecimal vatRate) {
        BigDecimal estimateTotalNet = sumEstimateNet(estimateClientPrices);
        BigDecimal totalDiscount = sumEffectiveDiscounts(effectiveDiscounts);

        BigDecimal totalNet = estimateTotalNet.subtract(totalDiscount);
        if (totalNet.signum() < 0) {
            totalNet = BigDecimal.ZERO;
        }
        totalNet = round2(totalNet);

        BigDecimal totalVat = round2(totalNet.multiply(normalizeVatRate(vatRate)).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        BigDecimal totalGross = round2(totalNet.add(totalVat));

        return new OfferTotals(totalNet, totalVat, totalGross);
    }

    /** Sums the estimate's client-facing final net across all lines, treating null/negative as zero. */
    private BigDecimal sumEstimateNet(Map<Long, BigDecimal> estimateClientPrices) {
        if (estimateClientPrices == null || estimateClientPrices.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal price : estimateClientPrices.values()) {
            if (price != null && price.signum() > 0) {
                sum = sum.add(price);
            }
        }
        return sum;
    }

    /**
     * Sums the effective discount amounts, re-clamping each to its own {@code [0, netBase]} so no
     * single discount removes more than its base (Requirements 2.7 / 10.2).
     */
    private BigDecimal sumEffectiveDiscounts(Map<Long, EffectiveDiscount> effectiveDiscounts) {
        if (effectiveDiscounts == null || effectiveDiscounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (EffectiveDiscount discount : effectiveDiscounts.values()) {
            if (discount == null) {
                continue;
            }
            sum = sum.add(clampAmount(discount));
        }
        return sum;
    }

    /** Clamps an effective discount amount to {@code [0, netBase]}; a null amount contributes zero. */
    private BigDecimal clampAmount(EffectiveDiscount discount) {
        BigDecimal amount = discount.amount();
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal netBase = discount.netBase();
        if (netBase != null && netBase.signum() > 0 && amount.compareTo(netBase) > 0) {
            return netBase;
        }
        if (netBase == null || netBase.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount;
    }

    private BigDecimal normalizeVatRate(BigDecimal vatRate) {
        if (vatRate == null || vatRate.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return vatRate;
    }

    private BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
