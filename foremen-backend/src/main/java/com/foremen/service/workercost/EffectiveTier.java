package com.foremen.service.workercost;

import java.math.BigDecimal;

import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;

/**
 * FOR-07-06 (design §Cost seam, R2.2–R2.4) — pure resolution of a worker's <strong>effective
 * tier</strong> from the worker user's own {@code workerType} and {@code seniorityPct}.
 *
 * <p>Folds seniority into the uplift so the unchanged {@code MarginCostService} core yields
 * {@code Base_Cost × (1 + tierUplift + seniorityPct)}, because
 * {@code tierCost(base, uplift(x)) = base × (1 + x)}:
 * <ul>
 *   <li>active non-base type: {@code uplift(type.tierPct + s)}, fallback = false;</li>
 *   <li>active base type: {@code s = 0} → the base tier (identical to FOR-06-10); {@code s > 0} →
 *       {@code uplift(s)}, fallback = false;</li>
 *   <li>{@code null} / inactive type: the base-type path above with {@code baseTierFallback = true}
 *       and a {@code null} worker-type id (R2.2).</li>
 * </ul>
 *
 * <p>Stateless and side-effect free; no Spring.
 */
public final class EffectiveTier {

    private EffectiveTier() {
        // pure static helper, never instantiated
    }

    /**
     * Resolve the effective tier for a worker.
     *
     * @param typeOrNull   the worker user's {@code workerType}; {@code null} or inactive ⇒ base fallback
     * @param seniorityPct the worker's seniority uplift fraction (e.g. {@code 0.05}); {@code null} ⇒ 0
     * @param baseType     the active base {@code WorkerType} row; {@code null} when unseeded (the cost
     *                     core then applies its default base share)
     * @return the resolved tier carrying id, fallback flag and the {@link WorkerTypeTier} to cost at
     */
    public static ResolvedTier of(WorkerTypeEntity typeOrNull, BigDecimal seniorityPct, WorkerTypeEntity baseType) {
        BigDecimal s = seniorityPct == null ? BigDecimal.ZERO : seniorityPct;

        if (typeOrNull != null && typeOrNull.isActive()) {
            if (!typeOrNull.isBase()) {
                BigDecimal tierPct = typeOrNull.getTierPct() == null ? BigDecimal.ZERO : typeOrNull.getTierPct();
                return new ResolvedTier(typeOrNull.getId(), false, WorkerTypeTier.uplift(tierPct.add(s)));
            }
            return new ResolvedTier(typeOrNull.getId(), false, baseWithSeniority(typeOrNull, s));
        }

        // Missing / inactive type ⇒ base tier (+ seniority), flagged (R2.2).
        return new ResolvedTier(null, true, baseWithSeniority(baseType, s));
    }

    /** Base tier when {@code s == 0} (FOR-06-10 parity), otherwise {@code uplift(s)}. */
    private static WorkerTypeTier baseWithSeniority(WorkerTypeEntity baseType, BigDecimal s) {
        if (s.signum() == 0) {
            return baseType == null ? null : WorkerTypeTier.base(baseType.getTierPct());
        }
        return WorkerTypeTier.uplift(s);
    }
}
