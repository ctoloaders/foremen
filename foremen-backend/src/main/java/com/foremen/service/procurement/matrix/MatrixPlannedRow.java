package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * FOR-06-05 (design §B1) — the pure plan-side input to the {@link MaterialsMatrixAssembler}: one
 * FOR-05-05b planned material, already resolved and localized by {@code MaterialsMatrixService}
 * (task 2.1) from the FOR-05-05b materials list plus the {@code PlannedMaterialCandidateProvider}.
 *
 * <p>It is an immutable value with no I/O so the assembler stays pure and property-testable without
 * Spring. The plan figures ({@link #plannedQuantity()}, {@link #plannedBrutto()}) are read verbatim
 * from the FOR-05-05b read model — the assembler never re-derives a plan quantity or price. The rows
 * are supplied in the FOR-05-05b order (branch → category → first-seen), which the assembler
 * preserves (R3.1).
 *
 * @param materialId      the concrete material id (row identity within its branch)
 * @param materialName    the concrete material localized name ({@code ru → pl → code})
 * @param branch          the branch (construction / finishing, R3.1)
 * @param category        the material type (category) localized name ({@code ru → pl → code})
 * @param unit            the norm unit, or {@code null}
 * @param plannedQuantity the FOR-05-05b reserve-adjusted effective planned quantity
 * @param plannedBrutto   the FOR-05-05b planned brutto total; {@code null} ⇒ pct null, excluded from
 *                        money totals (R1.4, R3.3)
 */
public record MatrixPlannedRow(
        Long materialId,
        String materialName,
        ConsumptionBranch branch,
        String category,
        String unit,
        BigDecimal plannedQuantity,
        BigDecimal plannedBrutto) {
}
