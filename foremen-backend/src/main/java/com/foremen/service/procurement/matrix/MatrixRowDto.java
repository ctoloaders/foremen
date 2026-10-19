package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * FOR-06-05 (design §Data Models, R1, R2, R4) — one planned-material row of the materials matrix: a
 * distinct {@code (branch, concreteMaterialId)} FOR-05-05b planned material, carrying the plan data
 * (read verbatim) side by side with the fact aggregation over every linked FOR-06-04 purchase line.
 *
 * <p>The fact side is a fold over the row's {@link #linkedLines()} (all analogs, all tiers at full
 * weight, R1.2): {@link #factBrutto()} is {@code Σ lineTotal} over every linked line (R1.3);
 * {@link #factQuantity()} sums {@code quantity} only over lines whose unit matches the norm
 * {@link #unit()} (R2.1); {@link #unitMismatchCount()} / {@link #unitMismatch()} count the rest
 * (R2.2). {@link #fulfilmentPercent()} is the money-based {@code factBrutto ÷ plannedBrutto × 100},
 * uncapped and {@code null} when {@link #plannedBrutto()} is {@code null}/0 (R1.3, R1.4);
 * {@link #fillIndicator()} is {@code min(pct, 100)} for the display bar and {@link #overPurchased()}
 * is {@code pct > 100} (R4.1). Mirrors the frontend {@code MatrixRow}.
 *
 * @param materialId        the concrete material id (row identity within its branch)
 * @param materialName      the concrete material localized name ({@code ru → pl → code})
 * @param branch            the row's branch (construction / finishing, R3.1)
 * @param category          the material type (category) localized name ({@code ru → pl → code})
 * @param unit              the planned norm unit, or {@code null}
 * @param plannedQuantity   the FOR-05-05b reserve-adjusted effective planned quantity
 * @param plannedBrutto     the FOR-05-05b planned brutto total; {@code null} ⇒ pct null, excluded
 *                          from money totals (R1.4, R3.3)
 * @param factQuantity      {@code Σ quantity} over same-unit linked lines only (R2.1)
 * @param unitMismatchCount the number of linked lines whose unit does not match the norm unit (R2.2)
 * @param unitMismatch      whether {@link #unitMismatchCount()} {@code > 0} (R2.2)
 * @param factBrutto        {@code Σ lineTotal} over every linked line (R1.3)
 * @param fulfilmentPercent the uncapped money-based % выборки; {@code null} when no denominator (R1.4)
 * @param fillIndicator     {@code min(fulfilmentPercent, 100)}; {@code null} when pct null (R4.1)
 * @param overPurchased     whether {@code fulfilmentPercent > 100} (R4.1)
 * @param linkedLineCount   the number of linked lines (R1.5)
 * @param linkedLines       the linked lines, for the per-material detail (R4.2)
 */
public record MatrixRowDto(
        Long materialId,
        String materialName,
        ConsumptionBranch branch,
        String category,
        String unit,
        BigDecimal plannedQuantity,
        BigDecimal plannedBrutto,
        BigDecimal factQuantity,
        int unitMismatchCount,
        boolean unitMismatch,
        BigDecimal factBrutto,
        BigDecimal fulfilmentPercent,
        BigDecimal fillIndicator,
        boolean overPurchased,
        int linkedLineCount,
        List<MatrixLinkedLineDto> linkedLines) {
}
