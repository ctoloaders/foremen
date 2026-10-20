package com.foremen.service.execution.marginmatrix;

import java.util.List;

/**
 * FOR-06-06 (design §Data Models) — the full margin-matrix read model returned by
 * {@code GET /api/projects/{projectId}/margin-matrix}: the work-category groups (one row per estimate
 * work item, with the plan × fact margin and the per-row report detail) and the project-level overall
 * dashboard (labour + linked material + indirect, with the indirect breakdown). Mirrored by the
 * frontend {@code MarginMatrixDto}.
 *
 * @param projectId  the owning project id
 * @param categories the work-category groups, in the estimate's category order (R4.1)
 * @param dashboard  the project-level overall dashboard (R3.4, R3.5)
 */
public record MarginMatrixDto(
        Long projectId,
        List<MarginCategoryGroupDto> categories,
        MarginDashboardDto dashboard) {
}
