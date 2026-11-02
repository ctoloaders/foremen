package com.foremen.service.estimate.matrix;

import java.util.List;

/**
 * One work row of the Estimate tab matrix (FOR-05-05, design §B6): the work item, its optional
 * {@code Room_Type_Attachment} ({@code roomTypeIds}, empty ⇒ attaches to all rooms), and one
 * {@link CellDto} per room column (in the same order as {@link EstimateMatrixDto#rooms()}). Mirrors
 * the frontend {@code WorkRowDto}.
 *
 * @param workItemId   the work item id
 * @param workItemName the work item display name (localized at the read layer)
 * @param roomTypeIds  the work's Room_Type_Attachment ids (empty ⇒ attaches to all rooms, R10.3)
 * @param cells        one cell per room column, aligned with {@link EstimateMatrixDto#rooms()}
 * @param packageVolume the per-work-row package summary value (FOR-05-05 Amendment A1): the total
 *                      package-allocated finishing volume across all of the row's cells — the sum of
 *                      the resolved quantity of every {@code appliedFromPackage} material line in the
 *                      row. Zero when the row has no package-flagged lines
 * @param packageMoney  the per-work-row package summary MONEY range (FOR-05-04 Change #4): the sum of
 *                      the per-line money contribution of every {@code appliedFromPackage} material
 *                      line across the row's cells — the SAME contribution used for cost, so it
 *                      collapses (min == max) when every package-flagged line in the row is concrete,
 *                      and is a band otherwise. {@code 0..0} when the row has no package-flagged lines
 * @param unitCode      the work's unit-of-measurement code (e.g. {@code M2}); {@code null} when unknown
 *                      (FOR-05-05 bug-report Round 5 #11 — the "total works" column)
 * @param unitName      the work's unit-of-measurement name, localized (RU/PL) at the read layer
 * @param unitPrice     the work's labour price per unit: the frozen {@code EstimateLine.unitPrice} when
 *                      the work is assigned in the estimate, otherwise the catalog
 *                      {@code WorkPrice.netPrice}; {@code null} when the work is unpriced
 */
public record WorkRowDto(
        Long workItemId,
        String workItemName,
        List<Long> roomTypeIds,
        List<CellDto> cells,
        java.math.BigDecimal packageVolume,
        MoneyRange packageMoney,
        String unitCode,
        String unitName,
        java.math.BigDecimal unitPrice) {

    /** A row without unit / unit-price information (the pre-Round-5 shape, used by tests). */
    public WorkRowDto(Long workItemId, String workItemName, List<Long> roomTypeIds, List<CellDto> cells,
                      java.math.BigDecimal packageVolume, MoneyRange packageMoney) {
        this(workItemId, workItemName, roomTypeIds, cells, packageVolume, packageMoney, null, null, null);
    }
}
