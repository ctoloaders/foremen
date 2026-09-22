package com.foremen.controller.model;

/**
 * List/read DTO for a {@code WorkItem}.
 *
 * <p>{@link #costCell} carries the FOR-04-19 work-catalog aggregation, collapsed to a package-less
 * shape by FOR-05-04 (Requirement 7.1): the single labour price plus the construction and finishing
 * material money ranges. It is a computed field attached onto the existing row (task 6.2/14.2), so
 * it never multiplies or splits the paginated distinct-{@code WorkItem} rows (Requirement 5.3).
 */
public record WorkItemDtoModel(Long id, Long workCategoryId, String workCategoryName, Long unitId, String unitName,
                               String name, String code, boolean active,
                               WorkCostCellDto costCell) {}
