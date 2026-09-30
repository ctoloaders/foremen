package com.foremen.service.estimate.margins;

import java.util.List;

/**
 * FOR-05-06 (design "Read-model DTOs") — the full Margins tab read model. Returned by
 * {@code GET /api/estimates/project/{projectId}/margins} and mirrored by the frontend
 * {@code MarginsListDto}.
 *
 * <p>A read-and-annotate projection over the kosztorys ({@code EstimateMatrixDto}) + the WorkerType
 * dictionary + material {@code cost_net}s: {@code workerTypes} are the ordered tier column headers
 * (R4.3); {@code groups} are the work rows grouped by work type (R4.2, R4.7); {@code dashboard}
 * summarizes per-tier labour totals and per-branch material totals (R5). The tab is read-only; nothing
 * is persisted (R4.1, R5.5).
 *
 * @param projectId   the owning project id
 * @param workerTypes the ordered tier headers (id, name, base, pct) for the matrix columns (R4.3)
 * @param groups      the margin rows grouped by work type, in kosztorys category order (R4.2, R4.7)
 * @param dashboard   the per-tier labour + per-branch material dashboard (R5)
 */
public record MarginsListDto(
        Long projectId,
        List<WorkerTypeRefDto> workerTypes,
        List<MarginWorkGroupDto> groups,
        MarginsDashboardDto dashboard) {
}
