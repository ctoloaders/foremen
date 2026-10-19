package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-05 (design §Data Models) — the full materials-matrix read model: the branch groups
 * (construction, finishing) each with its category-grouped planned-material rows and per-branch
 * dashboard, plus the project-wide grand-total dashboard (R3.2). Returned by
 * {@code GET /api/projects/{projectId}/materials-matrix} and mirrored by the frontend
 * {@code MaterialsMatrixDto}.
 *
 * <p>The grand total is the sum of the per-branch totals; {@link #grandAggregatePercent()} is the
 * uncapped {@code grandFactBrutto ÷ grandPlannedBrutto × 100}, {@code null} when the grand planned
 * brutto is 0 (R3.2).
 *
 * @param projectId             the owning project id
 * @param branches              the branch groups (construction, finishing)
 * @param grandPlannedBrutto    {@code Σ} per-branch planned brutto (R3.2)
 * @param grandFactBrutto       {@code Σ} per-branch fact brutto (R3.2)
 * @param grandAggregatePercent the uncapped grand aggregate % выборки; {@code null} when 0 denom (R3.2)
 */
public record MaterialsMatrixDto(
        Long projectId,
        List<MatrixBranchGroupDto> branches,
        BigDecimal grandPlannedBrutto,
        BigDecimal grandFactBrutto,
        BigDecimal grandAggregatePercent) {
}
