package com.foremen.service.estimate.margins;

import java.util.List;

/**
 * FOR-05-06 (design "Read-model DTOs") — a group of {@link MarginRowDto}s sharing the same work
 * category (R4.7). The Margins matrix groups rows <strong>by work type</strong> in the same category
 * ordering as the kosztorys ({@code EstimateMatrixDto.groups()}). Mirrors the frontend group shape.
 *
 * @param categoryId   the work category id (grouping key), or {@code null}
 * @param categoryName the work category display name, or {@code null}
 * @param rows         the margin rows in this group, in kosztorys row order
 */
public record MarginWorkGroupDto(
        Long categoryId,
        String categoryName,
        List<MarginRowDto> rows) {
}
