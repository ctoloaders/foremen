package com.foremen.service.estimate.matrix;

import java.util.List;

/**
 * A collapsible group of work rows sharing the same work category (FOR-05-05, design §B6, R2.1), with
 * the group's aggregated works / construction / finishing subtotals (R2.2). Mirrors the frontend
 * {@code WorkTypeGroupDto}.
 *
 * @param workCategoryId   the work category id
 * @param workCategoryName the work category display name (localized at the read layer)
 * @param rows             the work rows in this group
 * @param subtotals        the group's per-branch subtotals (R2.2)
 * @param packageVolume    the group's package summary subtotal (FOR-05-05 Amendment A1): the sum of
 *                         the group's rows' {@code packageVolume}
 * @param packageMoney     the group's package summary MONEY range (FOR-05-04 Change #4): the sum of
 *                         the group's rows' {@code packageMoney} — collapses (min == max) when every
 *                         package-flagged line in the group is concrete, a band otherwise
 */
public record WorkTypeGroupDto(
        Long workCategoryId,
        String workCategoryName,
        List<WorkRowDto> rows,
        BranchSubtotals subtotals,
        java.math.BigDecimal packageVolume,
        MoneyRange packageMoney) {
}
