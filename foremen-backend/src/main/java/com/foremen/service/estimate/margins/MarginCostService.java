package com.foremen.service.estimate.margins;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * FOR-05-06 (design §B3) — the single, pure place the service/material cost + margin rules live on the
 * backend. Mirrored by the frontend {@code marginsModel.ts} so the live client recompute and the
 * server read model agree exactly (same rounding, same tier and margin formulas).
 *
 * <p>All members are {@code static} and side-effect free: the class holds no state, does no I/O, and
 * needs no Spring context, so it is directly property-testable (task 4.4).
 *
 * <p>The cost model (the core rule):
 * <pre>
 *   Base_Cost    = round(baseShare × Offer_Service_Price  to 0.5 zł)   // half-up; baseShare = 0.40 by seed
 *   Tier_Cost(t) = Base_Cost × (1 + t.upliftOnBase)                    // base tier uplift = 0
 * </pre>
 * A {@code null}/zero offer yields a {@code null} (unavailable) base cost — never a fabricated value
 * (R2.4). Margins keep labour and materials separate (decision #4): {@link #labourMargin} works off the
 * offer, {@link #materialMargin} works off the retail, and nothing merges the two.
 */
public final class MarginCostService {

    /** The step the base cost rounds to: the nearest 0.5 zł (R2.5). */
    private static final BigDecimal ROUND_STEP = new BigDecimal("0.5");

    /** Scale used when dividing to derive a margin percentage (a fraction, e.g. {@code 0.4211}). */
    private static final int PCT_SCALE = 6;

    private MarginCostService() {
        // pure static helper, never instantiated
    }

    // ---------------------------------------------------------------------------------------------
    // Base cost — round(baseShare × offer to 0.5 zł), half-up (R2.1, R2.4, R2.5)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code Base_Cost} for the default base share of {@code 0.40} (the seeded base tier). Convenience
     * overload of {@link #baseCost(BigDecimal, BigDecimal)} for callers that do not carry a base tier.
     *
     * @param offerPrice the offer service price; {@code null} or {@code 0} ⇒ {@code null} (unavailable)
     * @return {@code round(0.40 × offer to 0.5 zł, HALF_UP)}, or {@code null} when the offer is
     *         {@code null}/zero
     */
    public static BigDecimal baseCost(BigDecimal offerPrice) {
        return baseCost(offerPrice, new BigDecimal("0.40"));
    }

    /**
     * {@code Base_Cost = round(baseShare × offer to the nearest 0.5 zł, HALF_UP)} (R2.1, R2.5). A
     * {@code null} or zero offer ⇒ {@code null} (unavailable — no fabricated value, R2.4).
     *
     * <p>Rounding to 0.5 is implemented by scaling to halves: {@code round(x / 0.5) × 0.5}, i.e.
     * {@code (x × 2)} rounded HALF_UP to a whole number, then {@code × 0.5}. The result is always a
     * multiple of {@code 0.5} at scale 2 (Property 1).
     *
     * @param offerPrice the offer service price; {@code null} or {@code 0} ⇒ {@code null}
     * @param baseShare  the base tier share of the offer (e.g. {@code 0.40}); {@code null} ⇒ {@code null}
     * @return the rounded base cost, or {@code null} when unavailable
     */
    public static BigDecimal baseCost(BigDecimal offerPrice, BigDecimal baseShare) {
        if (offerPrice == null || offerPrice.signum() == 0 || baseShare == null) {
            return null; // null/zero offer ⇒ unavailable (R2.4)
        }
        BigDecimal raw = offerPrice.multiply(baseShare);
        // round to nearest 0.5: divide by 0.5 (i.e. ×2), round HALF_UP to whole, multiply back by 0.5.
        BigDecimal steps = raw.divide(ROUND_STEP, 0, RoundingMode.HALF_UP);
        return steps.multiply(ROUND_STEP).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * {@code Base_Cost} resolved from a base {@link WorkerTypeTier} (its {@link WorkerTypeTier#tierPct()}
     * is the offer share). Delegates to {@link #baseCost(BigDecimal, BigDecimal)}.
     *
     * @param offerPrice the offer service price
     * @param baseTier   the base tier (its {@code tierPct} is the offer share); {@code null} ⇒ default 0.40
     * @return the rounded base cost, or {@code null} when unavailable
     */
    public static BigDecimal baseCost(BigDecimal offerPrice, WorkerTypeTier baseTier) {
        if (baseTier == null) {
            return baseCost(offerPrice);
        }
        return baseCost(offerPrice, baseTier.tierPct());
    }

    // ---------------------------------------------------------------------------------------------
    // Tier cost — base × (1 + uplift); base tier uplift = 0 (R2.2)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code Tier_Cost = baseCost × (1 + tier.upliftOnBase)} (R2.2). For the base tier the uplift is
     * {@code 0}, so {@code tierCost(base, baseTier) == baseCost}. A {@code null} base cost ⇒ {@code null}
     * (unavailable propagates, Property 8).
     *
     * @param baseCost the {@link #baseCost} value; {@code null} ⇒ {@code null}
     * @param tier     the tier; the base tier contributes a {@code 0} uplift, a non-base tier
     *                 contributes {@code tierPct} as the uplift-on-base fraction; {@code null} ⇒ base
     * @return {@code baseCost × (1 + uplift)}, or {@code null} when {@code baseCost} is {@code null}
     */
    public static BigDecimal tierCost(BigDecimal baseCost, WorkerTypeTier tier) {
        if (baseCost == null) {
            return null; // unavailable base ⇒ unavailable tier cost (Property 8)
        }
        if (tier == null || tier.base()) {
            return baseCost; // base tier uplift = 0 ⇒ tierCost == baseCost (R2.2)
        }
        BigDecimal uplift = tier.tierPct() == null ? BigDecimal.ZERO : tier.tierPct();
        return baseCost.multiply(BigDecimal.ONE.add(uplift));
    }

    // ---------------------------------------------------------------------------------------------
    // Labour margin — offer − tierCost, pct of offer (R4.4, kept separate from material)
    // ---------------------------------------------------------------------------------------------

    /**
     * Labour {@link Margin} for a tier: {@code amount = offer − tierCost}, {@code pct = amount / offer}
     * (R4.4). Either input {@code null} ⇒ {@link Margin#UNAVAILABLE} (Property 8). The percentage is
     * {@code null} when the offer is zero (no division by zero).
     *
     * @param offer    the offer service price; {@code null} ⇒ unavailable
     * @param tierCost the tier's cost ({@link #tierCost}); {@code null} ⇒ unavailable
     * @return the labour margin, or {@link Margin#UNAVAILABLE}
     */
    public static Margin labourMargin(BigDecimal offer, BigDecimal tierCost) {
        return margin(offer, tierCost, offer);
    }

    // ---------------------------------------------------------------------------------------------
    // Material margin — retail − cost, pct of retail, per branch (R4.4, separate from labour)
    // ---------------------------------------------------------------------------------------------

    /**
     * Material {@link Margin} for a branch: {@code amount = retailNet − costNet},
     * {@code pct = amount / retailNet} (R4.4). Kept independent of the labour margin and computed per
     * branch by the assembler. Either input {@code null} ⇒ {@link Margin#UNAVAILABLE} (R4.5, Property 8);
     * the percentage is {@code null} when the retail is zero.
     *
     * @param retailNet the material retail net (the reference base); {@code null} ⇒ unavailable
     * @param costNet   the material self-cost; {@code null} ⇒ unavailable
     * @return the material margin, or {@link Margin#UNAVAILABLE}
     */
    public static Margin materialMargin(BigDecimal retailNet, BigDecimal costNet) {
        return margin(retailNet, costNet, retailNet);
    }

    /**
     * The shared margin fold: {@code amount = reference − cost}, {@code pct = amount / base}. Any
     * {@code null} of {@code reference}/{@code cost} ⇒ {@link Margin#UNAVAILABLE}; a {@code null}/zero
     * {@code base} yields a {@code null} percentage (the amount is still returned).
     */
    private static Margin margin(BigDecimal reference, BigDecimal cost, BigDecimal base) {
        if (reference == null || cost == null) {
            return Margin.UNAVAILABLE; // unavailable propagation (Property 8)
        }
        BigDecimal amount = reference.subtract(cost);
        BigDecimal pct = (base == null || base.signum() == 0)
                ? null // no division by zero, no fabricated percentage
                : amount.divide(base, PCT_SCALE, RoundingMode.HALF_UP);
        return new Margin(amount, pct);
    }
}
