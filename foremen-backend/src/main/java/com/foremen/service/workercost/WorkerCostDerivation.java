package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.Map;

import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.estimate.margins.MarginCostService;
import com.foremen.service.estimate.margins.WorkerTypeTier;

/**
 * FOR-06-10 (design §B1) — the pure, stateless worker-cost derivation helpers the single cost-side
 * seam ({@code WorkerCostService}) and FOR-06-06 both reuse. Mirrors the shape of the FOR-05-06
 * {@link com.foremen.service.estimate.margins.MarginCostService} core: all members are {@code static}
 * and side-effect free, so the resolution and the cost fold are directly jqwik-testable without a
 * Spring context.
 *
 * <p>This helper delivers both the <strong>worker-type resolution</strong> {@link #tierFor} and the
 * <strong>cost math</strong> ({@link #unitServiceCost} / {@link #costValue}). The cost math is composed
 * entirely from the same {@link MarginCostService} core, so there is exactly one cost formula shared
 * with the estimate side (R1.2, R4.3).
 */
public final class WorkerCostDerivation {

    private WorkerCostDerivation() {
        // pure static helper, never instantiated
    }

    // ---------------------------------------------------------------------------------------------
    // Worker-type resolution — membership workerType → base-tier fallback (R2.1, R2.2, R2.4, R2.5)
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolve the {@code Resolved_Worker_Type} for a reported worker from a pre-built
     * {@code userId → active workerType} map (built once per project to avoid an N+1), with a safe
     * base-tier fallback (design §B1):
     * <ul>
     *   <li>WHERE the worker has an <strong>active</strong> assigned worker type in the map, that type
     *       is used — {@code baseTierFallback = false}, carrying the type's id, and the
     *       {@link WorkerTypeTier} built from its {@code tierPct} ({@link WorkerTypeTier#base} for the
     *       base row, {@link WorkerTypeTier#uplift} otherwise) (R2.1, R2.3);</li>
     *   <li>WHERE the worker id is {@code null}, has no membership entry (missing), has a {@code null}
     *       assigned type (Uncategorized), or the assigned type is <strong>inactive</strong>, the
     *       resolution falls back to the <strong>base tier</strong> with {@code baseTierFallback = true}
     *       and a {@code null} worker-type id — never an error, never a fabricated cost (R2.2, R2.4).</li>
     * </ul>
     *
     * <p>The <strong>base tier</strong> is the active {@code WorkerType} row flagged {@code base}
     * (R2.3); the caller resolves it once from {@code WorkerTypeDao.findActiveOrdered()} and passes it
     * as {@code baseType}. A {@code null} {@code baseType} still yields a base-tier fallback result
     * whose {@link ResolvedTier#tier()} is {@code null} (the {@code MarginCostService} core then
     * applies its own default base share), so the derivation stays well-formed.
     *
     * <p>Deterministic and side-effect free (R2.5): it reads the map and the passed worker types and
     * computes; it writes nothing and mutates no report, membership, or worker type.
     *
     * @param workerId      the reported worker's user id; {@code null} ⇒ base-tier fallback
     * @param activeTypeByWorkerId a pre-built {@code userId → active workerType} map (an INACTIVE type
     *                      MUST NOT be present as a value); {@code null} ⇒ treated as empty
     * @param baseType      the active base {@code WorkerType} (the {@code is_base = true} row), or
     *                      {@code null} when unseeded
     * @return the resolved tier: the id (or {@code null} on fallback), the {@code baseTierFallback}
     *         flag, and the {@link WorkerTypeTier} the unit cost is computed at
     */
    public static ResolvedTier tierFor(Long workerId,
                                       Map<Long, WorkerTypeEntity> activeTypeByWorkerId,
                                       WorkerTypeEntity baseType) {
        WorkerTypeEntity assigned = (workerId == null || activeTypeByWorkerId == null)
                ? null
                : activeTypeByWorkerId.get(workerId);

        if (assigned != null && assigned.isActive()) {
            WorkerTypeTier tier = assigned.isBase()
                    ? WorkerTypeTier.base(assigned.getTierPct())
                    : WorkerTypeTier.uplift(assigned.getTierPct());
            return new ResolvedTier(assigned.getId(), false, tier);
        }

        // Base-tier fallback: null worker / missing membership / Uncategorized (null type) / inactive type.
        // The resolved id is null on a fallback (the documented ResolvedTier / WorkerCostLineDto
        // contract, R2.4) so consumers distinguish an explicit membership tier from the base-tier
        // fallback by the id alone — identical to FOR-06-06 MarginMatrixService.resolveWorkerTier.
        WorkerTypeTier baseTier = baseType == null ? null : WorkerTypeTier.base(baseType.getTierPct());
        return new ResolvedTier(null, true, baseTier);
    }

    /**
     * The resolved worker tier for a reported work: the resolved {@code WorkerType} id (or {@code null}
     * when the base tier was reached via fallback), the {@code baseTierFallback} flag surfaced to
     * consumers (R2.4), and the {@link WorkerTypeTier} the unit {@code Worker_Service_Cost} is computed
     * at.
     *
     * @param workerTypeId     the resolved worker type id, or {@code null} on a base-tier fallback
     * @param baseTierFallback whether the base tier was used via fallback (R2.4)
     * @param tier             the resolved {@link WorkerTypeTier} (may be {@code null} when no base tier
     *                         is seeded — the {@code MarginCostService} core then uses its default share)
     */
    public record ResolvedTier(Long workerTypeId, boolean baseTierFallback, WorkerTypeTier tier) {
    }

    // ---------------------------------------------------------------------------------------------
    // Cost math — the single cost formula, composed from the MarginCostService core (R1.2, R1.3, R1.5)
    // ---------------------------------------------------------------------------------------------

    /**
     * The unit {@code Worker_Service_Cost} for a reported work: the per-unit service cost at the
     * resolved tier, derived through <strong>exactly one</strong> cost formula — the shared
     * {@link MarginCostService} core (R1.2, R4.3):
     * <pre>
     *   unitServiceCost = tierCost(baseCost(rate, baseTier), tier)
     * </pre>
     * There is no second formula here: {@link MarginCostService#baseCost(BigDecimal, WorkerTypeTier)}
     * applies the base share (and its {@code 0.40} default when {@code baseTier} is {@code null}) and
     * {@link MarginCostService#tierCost(BigDecimal, WorkerTypeTier)} applies the tier uplift.
     *
     * <p>An <strong>unavailable</strong> rate ({@code null} or zero) yields a {@code null} unit cost —
     * never a fabricated zero (R1.5) — because {@code baseCost} returns {@code null} for a {@code null}
     * /zero offer and {@code tierCost} propagates that {@code null}.
     *
     * <p>Deterministic, static, and side-effect free: it only computes.
     *
     * @param rate     the worker's rate (the offer service price fed to the core); {@code null}/zero ⇒
     *                 {@code null} (unavailable, R1.5)
     * @param tier     the resolved {@link WorkerTypeTier} the cost is computed at; {@code null} ⇒ base
     * @param baseTier the base {@link WorkerTypeTier} (its share scales the rate); {@code null} ⇒ the
     *                 core's default base share
     * @return the unit service cost, or {@code null} when the rate is unavailable
     */
    public static BigDecimal unitServiceCost(BigDecimal rate, WorkerTypeTier tier, WorkerTypeTier baseTier) {
        return MarginCostService.tierCost(MarginCostService.baseCost(rate, baseTier), tier);
    }

    /**
     * The extended {@code Worker_Service_Cost} for a reported work: the unit
     * {@link #unitServiceCost(BigDecimal, WorkerTypeTier, WorkerTypeTier)} multiplied by the reported
     * quantity, scaling linearly with quantity (R1.3):
     * <pre>
     *   costValue = unitServiceCost(rate, tier, baseTier) × quantity
     * </pre>
     *
     * <p>An <strong>unavailable</strong> rate ({@code null}/zero) propagates to a {@code null} cost
     * value — never a fabricated zero (R1.5) — because the unit cost is {@code null} and the
     * multiplication short-circuits. A {@code null} quantity likewise short-circuits to {@code null}
     * (an unknown quantity cannot produce a fabricated cost).
     *
     * <p>Deterministic, static, and side-effect free: it only computes.
     *
     * @param rate     the worker's rate; {@code null}/zero ⇒ {@code null} (unavailable, R1.5)
     * @param tier     the resolved {@link WorkerTypeTier} the unit cost is computed at; {@code null} ⇒ base
     * @param baseTier the base {@link WorkerTypeTier}; {@code null} ⇒ the core's default base share
     * @param quantity the reported quantity the unit cost scales by; {@code null} ⇒ {@code null}
     * @return {@code unitServiceCost × quantity}, or {@code null} when the rate or quantity is unavailable
     */
    public static BigDecimal costValue(BigDecimal rate,
                                       WorkerTypeTier tier,
                                       WorkerTypeTier baseTier,
                                       BigDecimal quantity) {
        BigDecimal unit = unitServiceCost(rate, tier, baseTier);
        if (unit == null || quantity == null) {
            return null; // unavailable rate or unknown quantity ⇒ null, never a fabricated zero (R1.5)
        }
        return unit.multiply(quantity);
    }
}
