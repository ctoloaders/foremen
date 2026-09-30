package com.foremen.service.estimate.margins;

import java.math.BigDecimal;

/**
 * FOR-05-06 (design §B3) — the minimal cost-tier parameter consumed by {@link MarginCostService}.
 *
 * <p>A pure value carrying only what the on-the-fly service-cost formula needs: whether this is the
 * <strong>base</strong> tier and the tier's percentage. This is deliberately decoupled from the JPA
 * {@code WorkerTypeEntity} so the cost core stays pure and property-testable without Spring; the
 * assembler (task 4.2) maps each {@code WorkerTypeEntity} to a {@code WorkerTypeTier} before calling
 * the core.
 *
 * <p>Semantics of {@link #tierPct()} (mirrors {@code WorkerTypeEntity.tierPct}):
 * <ul>
 *   <li>for the <strong>base</strong> tier ({@code base == true}) it is the SHARE of the offer price
 *       (e.g. {@code 0.40}) used by {@link MarginCostService#baseCost(BigDecimal, WorkerTypeTier)};</li>
 *   <li>for a <strong>non-base</strong> tier it is the UPLIFT ON THE BASE cost, expressed as a
 *       fraction (e.g. {@code 0.10}, {@code 0.265}, {@code 0.65}), so
 *       {@code tierCost = baseCost × (1 + tierPct)}.</li>
 * </ul>
 *
 * @param base    whether this is the base tier (exactly one tier in the dictionary is base)
 * @param tierPct the tier percentage: an offer share for the base tier, an uplift-on-base fraction
 *                for non-base tiers; never {@code null} for a valid dictionary row
 */
public record WorkerTypeTier(boolean base, BigDecimal tierPct) {

    /** A base tier carrying the given offer share (e.g. {@code 0.40}). */
    public static WorkerTypeTier base(BigDecimal offerShare) {
        return new WorkerTypeTier(true, offerShare);
    }

    /** A non-base tier carrying the given uplift-on-base fraction (e.g. {@code 0.10}). */
    public static WorkerTypeTier uplift(BigDecimal upliftOnBase) {
        return new WorkerTypeTier(false, upliftOnBase);
    }
}
