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
 */
public record WorkTypeGroupDto(
        Long workCategoryId,
        String workCategoryName,
        List<WorkRowDto> rows,
        BranchSubtotals subtotals) {
}
