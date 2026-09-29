package com.foremen.controller.model;

import java.util.List;

/**
 * List/read DTO for a {@code WorkItem}.
 *
 * <p>{@link #costCell} carries the FOR-04-19 work-catalog aggregation, collapsed to a package-less
 * shape by FOR-05-04 (Requirement 7.1): the single labour price plus the construction and finishing
 * material money ranges. It is a computed field attached onto the existing row (task 6.2/14.2), so
 * it never multiplies or splits the paginated distinct-{@code WorkItem} rows (Requirement 5.3).
 *
 * <p>{@link #packages} lists the offer packages this work is a MEMBER of (localized {@link RefDto}
 * id + name) — every package with a {@code WorkPackageOverride(member=true)} row for this work. It is
 * batch-populated per page (no per-row N+1) and, like {@code costCell}, is attached onto the existing
 * row, so it never multiplies the paginated distinct-{@code WorkItem} rows. The list is filterable via
 * the generic query DSL as {@code packages.id==<id>} / {@code packages.id~in~<id1>,<id2>}, backed by an
 * EXISTS subquery over the membership rows (single and multiple both supported).
 */
public record WorkItemDtoModel(Long id, Long workCategoryId, String workCategoryName, Long unitId, String unitName,
                               String name, String code, boolean active,
                               WorkCostCellDto costCell, List<RefDto> packages) {}
