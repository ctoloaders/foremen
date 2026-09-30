package com.foremen.service.estimate.margins;

import java.util.List;

/**
 * FOR-05-06 (design "Read-model DTOs") — the Margins tab cost dashboard (R5). Because the project is in
 * DRAFT, it presents <strong>every</strong> worker-type tier variant side by side ({@code labourByTier}),
 * plus the project-wide material totals kept separate <strong>per branch</strong> (decision #4, R5.3).
 * Every figure is the Σ of the corresponding per-row figures (Property 9). Mirrors the frontend
 * {@code dashboard}.
 *
 * @param labourByTier      the per-tier labour totals (offer / cost / margin), in dictionary order (R5.2)
 * @param constructionTotal the project-wide construction material retail / cost / margin (R5.3)
 * @param finishingTotal    the project-wide finishing material retail / cost / margin (R5.3)
 */
public record MarginsDashboardDto(
        List<TierTotalDto> labourByTier,
        BranchMaterialDto constructionTotal,
        BranchMaterialDto finishingTotal) {
}
