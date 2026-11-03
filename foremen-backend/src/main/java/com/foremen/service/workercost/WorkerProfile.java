package com.foremen.service.workercost;

import java.math.BigDecimal;

import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;

/**
 * FOR-07-06 (design §Cost seam, R2.1) — the cost-relevant profile of a worker <strong>user</strong>:
 * the user's own {@code workerType} (as stored; may be {@code null} or inactive) and
 * {@code seniorityPct} (never {@code null}, defaults to 0). Built by
 * {@link WorkerCostService#workerProfilesById(java.util.Collection)}; the tier is derived from it via
 * {@link EffectiveTier}, never from {@code ProjectMember.workerType}.
 *
 * @param workerId     the worker user id
 * @param workerType   the user's {@code workerType} as stored ({@code null} / inactive ⇒ base fallback)
 * @param seniorityPct the user's seniority uplift fraction; never {@code null}
 */
public record WorkerProfile(Long workerId, WorkerTypeEntity workerType, BigDecimal seniorityPct) {

    public WorkerProfile {
        seniorityPct = seniorityPct == null ? BigDecimal.ZERO : seniorityPct;
    }

    /**
     * Resolve the effective tier for a (possibly absent) profile. A missing profile (unknown worker)
     * resolves exactly like a worker with no type and zero seniority — the flagged base tier (R2.2).
     *
     * @param profileOrNull the worker's profile, or {@code null} when unknown
     * @param baseType      the active base {@code WorkerType}; {@code null} when unseeded
     * @return the resolved tier
     */
    public static ResolvedTier resolve(WorkerProfile profileOrNull, WorkerTypeEntity baseType) {
        if (profileOrNull == null) {
            return EffectiveTier.of(null, BigDecimal.ZERO, baseType);
        }
        return EffectiveTier.of(profileOrNull.workerType(), profileOrNull.seniorityPct(), baseType);
    }
}
