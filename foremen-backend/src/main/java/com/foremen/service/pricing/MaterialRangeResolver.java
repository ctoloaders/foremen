package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.service.pricing.MaterialBatchLookup.BatchKey;

/**
 * Computes the FOR-04-19 <b>money price-range (вилка)</b> per {@code (work item, offer package)},
 * split into a <b>construction</b> and a <b>finishing</b> branch (Requirement 4). Nothing is ever
 * stored: the ranges are derived at read time from already-loaded consumption rows plus the analog
 * {@link TypeBatch}es supplied by {@link MaterialBatchLookup}.
 *
 * <p>The resolver is a pure, total, deterministic function of its inputs — it performs no I/O, holds
 * no state, and always yields the same {@code {min, max}} for the same set of rows and prices
 * (Requirement 4.7). To let the property tests exercise the aggregation without Spring, the core
 * logic lives in {@code static} helpers ({@link #typeBatchRange}, {@link #branchRange},
 * {@link #compute}); the {@code @Component} instance methods delegate to them verbatim.
 *
 * <p>The computation rules (Requirement 4.1–4.7):
 * <ol>
 *   <li><b>Type-level batch range</b> for {@code (normQty, TypeBatch)}:
 *       {@code min = normQty × MIN(retailNet)}, {@code max = normQty × MAX(retailNet)} over the
 *       batch's non-null {@code retailNet}s. Several materials ⇒ a real {@code min..max}; a single
 *       material ⇒ {@code min == max}; an EMPTY batch ⇒ {@code 0..0} (Requirement 4.2, 4.6). The
 *       {@code null}-{@code retailNet} exclusion already happened in {@link MaterialBatchLookup}, so
 *       the batch's list contains only priced materials.</li>
 *   <li><b>Distinct-type batching</b>: when two rows of the same {@code (work, package, branch)}
 *       reference the same material type, the type-level batch range is computed ONCE per distinct
 *       type and counted once (Requirement 4.2).</li>
 *   <li><b>Branch range</b>: the SUM over the branch's DISTINCT material types of the type-level
 *       batch {@code min} (the range min) and {@code max} (the range max). Only money is summed —
 *       physical quantities in different units are NEVER added (Requirement 4.3, 4.5).</li>
 *   <li><b>Empty branch ⇒ explicit {@code 0}</b>: a branch with no rows returns
 *       {@link MoneyRange#ZERO} ({@code 0..0}), distinguishable from an absent computation
 *       (Requirement 4.4).</li>
 *   <li><b>Determinism</b>: {@link BigDecimal#min}/{@link BigDecimal#max} folded over an
 *       order-independent aggregate keyed by material type (Requirement 4.7).</li>
 * </ol>
 */
@Component
public class MaterialRangeResolver {

    /**
     * Money display scale: computed money amounts (the вилка) are presented to 2 decimals, rounded
     * UP (towards positive infinity) to the nearest grosz, so a shown band never understates the
     * cost. {@code stored} prices are already at scale 2 ({@code numeric(12,2)}); this governs the
     * DERIVED {@code normQty × retailNet} products only (FOR-04-19 price normalization).
     */
    static final int MONEY_SCALE = 2;
    static final RoundingMode MONEY_ROUNDING = RoundingMode.CEILING;

    /** Rounds a derived money amount UP to {@link #MONEY_SCALE} decimals (never {@code null}). */
    static BigDecimal money(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * A computed money range in PLN per one work-unit. {@link #ZERO} is the explicit
     * {@code MoneyRange(0, 0)} used for an empty batch or an empty branch (never {@code null}).
     *
     * @param min the low end of the band (never {@code null})
     * @param max the high end of the band (never {@code null})
     */
    public record MoneyRange(BigDecimal min, BigDecimal max) {

        /** The explicit zero range used for an empty batch / empty branch (Requirement 4.2, 4.4). */
        public static final MoneyRange ZERO = new MoneyRange(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Both branch money ranges for one {@code (work item, offer package)} — each an explicit
     * {@link MoneyRange#ZERO} when its branch has no consumption (Requirement 4.1, 4.4).
     *
     * @param construction the construction-branch money range (never {@code null})
     * @param finishing    the finishing-branch money range (never {@code null})
     */
    public record BranchRanges(MoneyRange construction, MoneyRange finishing) {
    }

    /**
     * A minimal, pure input row for the range computation, decoupled from the JPA entity so the
     * property tests can construct inputs directly. It carries only what the aggregation needs: the
     * row's {@code branch}, its analog-group material {@code typeId}, and its {@code normQty}
     * (material-unit per one work-unit).
     *
     * @param branch        the row's branch (construction / finishing)
     * @param materialTypeId the analog-group material type id
     * @param normQty       the consumption norm (material-unit per one work-unit)
     */
    public record ConsumptionRowInput(ConsumptionBranch branch, Long materialTypeId, BigDecimal normQty) {
    }

    /**
     * A provider of the analog {@link TypeBatch} for a {@code (offerPackageId, materialTypeId, branch)}
     * key. Backed at runtime by the map {@link MaterialBatchLookup#load(Collection)} returns; the
     * property tests supply an in-memory implementation.
     */
    @FunctionalInterface
    public interface BatchProvider {
        /**
         * Returns the analog batch for the given key, or {@code null} when none was loaded (treated
         * as an empty batch ⇒ {@code 0..0}).
         */
        TypeBatch batchFor(Long offerPackageId, Long materialTypeId, ConsumptionBranch branch);
    }

    // ---------------------------------------------------------------------------------------------
    // Pure static helpers (the property-test entry points). The @Component methods delegate to them.
    // ---------------------------------------------------------------------------------------------

    /**
     * Type-level batch range for one row: {@code normQty × [MIN..MAX retailNet]} over the batch's
     * non-null {@code retailNet}s; an EMPTY (or {@code null}) batch, a {@code null} {@code normQty},
     * or a batch with no prices ⇒ {@link MoneyRange#ZERO} (Requirement 4.2, 4.6).
     *
     * @param normQty the consumption norm (material-unit per one work-unit)
     * @param batch   the analog batch of the row's material type in the row's package
     * @return the type-level money band {@code normQty × [min..max]}, or {@code 0..0}
     */
    public static MoneyRange typeBatchRange(BigDecimal normQty, TypeBatch batch) {
        if (normQty == null || batch == null || batch.retailNets() == null) {
            return MoneyRange.ZERO;
        }
        BigDecimal minRetail = null;
        BigDecimal maxRetail = null;
        for (BigDecimal retailNet : batch.retailNets()) {
            if (retailNet == null) {
                continue; // defensive; MaterialBatchLookup already excludes null retailNets
            }
            minRetail = (minRetail == null) ? retailNet : minRetail.min(retailNet);
            maxRetail = (maxRetail == null) ? retailNet : maxRetail.max(retailNet);
        }
        if (minRetail == null) {
            return MoneyRange.ZERO; // empty batch — the norm still shows in the drill-in with a 0 cost
        }
        // Round each derived band edge UP to 2 decimals (grosz). branchRange sums these already-
        // rounded per-type values, so a branch total equals the sum of the displayed per-type bands.
        return new MoneyRange(money(normQty.multiply(minRetail)), money(normQty.multiply(maxRetail)));
    }

    /**
     * Branch money range for one {@code (work item, offer package, branch)}: the SUM over the
     * branch's DISTINCT material types of their type-level batch {@code min}/{@code max}. No rows for
     * the branch ⇒ explicit {@link MoneyRange#ZERO} (Requirement 4.3, 4.4). Physical quantities are
     * never summed across units — only money (Requirement 4.5). Distinct-type batching folds
     * repeated types once (Requirement 4.2).
     *
     * @param offerPackageId the offer package the rows belong to (drives batch resolution)
     * @param rowsOfBranch   the consumption rows of a single branch (may be {@code null}/empty)
     * @param batches        the analog-batch provider
     * @return the branch money range, or {@code 0..0} when the branch is empty
     */
    public static MoneyRange branchRange(Long offerPackageId,
                                         Collection<ConsumptionRowInput> rowsOfBranch,
                                         BatchProvider batches) {
        if (rowsOfBranch == null || rowsOfBranch.isEmpty()) {
            return MoneyRange.ZERO;
        }

        // Distinct-type batching: compute each type's batch range once. LinkedHashMap keeps the
        // fold order stable, though the min/max aggregate is order-independent anyway (determinism).
        Map<Long, MoneyRange> perType = new LinkedHashMap<>();
        boolean sawRow = false;
        for (ConsumptionRowInput row : rowsOfBranch) {
            if (row == null || row.materialTypeId() == null) {
                continue;
            }
            sawRow = true;
            if (perType.containsKey(row.materialTypeId())) {
                continue; // already batched this distinct type (Requirement 4.2)
            }
            ConsumptionBranch branch = row.branch();
            TypeBatch batch = (batches == null)
                    ? null
                    : batches.batchFor(offerPackageId, row.materialTypeId(), branch);
            perType.put(row.materialTypeId(), typeBatchRange(row.normQty(), batch));
        }

        if (!sawRow) {
            return MoneyRange.ZERO;
        }

        // Sum money-only over the distinct types (Requirement 4.3, 4.5).
        BigDecimal min = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        for (MoneyRange typeRange : perType.values()) {
            min = min.add(typeRange.min());
            max = max.add(typeRange.max());
        }
        return new MoneyRange(min, max);
    }

    /**
     * Both branch ranges for a {@code (work item, offer package)}: the construction range and the
     * finishing range, each an explicit {@link MoneyRange#ZERO} when its branch is empty
     * (Requirement 4.1, 4.4).
     *
     * @param offerPackageId       the offer package the rows belong to
     * @param rowsForWorkPackage   all consumption rows for the {@code (work, package)} (both branches)
     * @param batches              the analog-batch provider
     * @return both branch money ranges (never {@code null}, each sub-range never {@code null})
     */
    public static BranchRanges compute(Long offerPackageId,
                                       Collection<ConsumptionRowInput> rowsForWorkPackage,
                                       BatchProvider batches) {
        Map<ConsumptionBranch, List<ConsumptionRowInput>> byBranch = new LinkedHashMap<>();
        if (rowsForWorkPackage != null) {
            for (ConsumptionRowInput row : rowsForWorkPackage) {
                if (row == null || row.branch() == null) {
                    continue;
                }
                byBranch.computeIfAbsent(row.branch(), k -> new java.util.ArrayList<>()).add(row);
            }
        }
        MoneyRange construction = branchRange(
                offerPackageId, byBranch.get(ConsumptionBranch.construction), batches);
        MoneyRange finishing = branchRange(
                offerPackageId, byBranch.get(ConsumptionBranch.finishing), batches);
        return new BranchRanges(construction, finishing);
    }

    // ---------------------------------------------------------------------------------------------
    // @Component instance methods — thin delegations to the pure helpers above.
    // ---------------------------------------------------------------------------------------------

    /** @see #typeBatchRange(BigDecimal, TypeBatch) */
    public MoneyRange typeBatchRangeFor(BigDecimal normQty, TypeBatch batch) {
        return typeBatchRange(normQty, batch);
    }

    /** @see #branchRange(Long, Collection, BatchProvider) */
    public MoneyRange branchRangeFor(Long offerPackageId,
                                     Collection<ConsumptionRowInput> rowsOfBranch,
                                     BatchProvider batches) {
        return branchRange(offerPackageId, rowsOfBranch, batches);
    }

    /** @see #compute(Long, Collection, BatchProvider) */
    public BranchRanges computeFor(Long offerPackageId,
                                   Collection<ConsumptionRowInput> rowsForWorkPackage,
                                   BatchProvider batches) {
        return compute(offerPackageId, rowsForWorkPackage, batches);
    }

    /**
     * Adapts a {@code Map<BatchKey, TypeBatch>} (as returned by
     * {@link MaterialBatchLookup#load(Collection)}) into a {@link BatchProvider}, so the runtime
     * aggregation can feed the pure helpers with the loaded batches.
     *
     * @param loaded the loaded batches keyed by {@link BatchKey}
     * @return a {@link BatchProvider} backed by the given map
     */
    public static BatchProvider providerOf(Map<BatchKey, TypeBatch> loaded) {
        Function<BatchKey, TypeBatch> lookup =
                (loaded == null) ? key -> null : loaded::get;
        return (offerPackageId, materialTypeId, branch) ->
                lookup.apply(new BatchKey(offerPackageId, materialTypeId, branch));
    }
}
