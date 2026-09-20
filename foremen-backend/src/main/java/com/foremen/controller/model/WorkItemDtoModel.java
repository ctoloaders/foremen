package com.foremen.controller.model;

import java.util.Map;

/**
 * List/read DTO for a {@code WorkItem}.
 *
 * <p>{@link #packagePivot} carries the FOR-04-19 work-catalog aggregation, keyed by
 * {@code offerPackage.id}: per seeded package the THREE prices for that {@code (work, package)} —
 * the FOR-04-12b labour price plus the construction and finishing material money ranges. The
 * frontend renders one pivot column per seeded package from this map. It is a computed field
 * attached onto the existing row (task 6.2), so it never multiplies or splits the paginated
 * distinct-{@code WorkItem} rows (Requirement 5.3).
 */
public record WorkItemDtoModel(Long id, Long workCategoryId, String workCategoryName, Long unitId, String unitName,
                               String name, String code, boolean active,
                               Map<Long, WorkCatalogPackageCellDto> packagePivot) {}
