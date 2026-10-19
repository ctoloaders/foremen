package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * FOR-06-05 (design §Data Models, R3.1, R3.2) — one branch group of the materials matrix
 * (construction / finishing): its category groups plus the per-branch dashboard totals folded over
 * the branch's rows with a non-null planned brutto (R3.2, R3.3).
 *
 * <p>{@link #aggregatePercent()} is the uncapped {@code Σ factBrutto ÷ Σ plannedBrutto × 100}, and is
 * {@code null} when the branch's planned brutto total is 0 (no meaningful denominator). Mirrors the
 * frontend {@code MatrixBranchGroup}.
 *
 * @param branch           the branch (construction / finishing)
 * @param categories       the branch's category groups, in FOR-05-05b order
 * @param plannedBrutto    {@code Σ plannedBrutto} over non-null-brutto rows (R3.2)
 * @param factBrutto       {@code Σ factBrutto} over non-null-brutto rows (R3.2)
 * @param aggregatePercent the uncapped aggregate % выборки; {@code null} when planned brutto 0 (R3.2)
 */
public record MatrixBranchGroupDto(
        ConsumptionBranch branch,
        List<MatrixCategoryGroupDto> categories,
        BigDecimal plannedBrutto,
        BigDecimal factBrutto,
        BigDecimal aggregatePercent) {
}
