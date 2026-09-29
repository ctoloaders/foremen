package com.foremen.service.model;

import java.util.ArrayList;
import java.util.List;

import com.foremen.controller.model.RefDto;
import com.foremen.controller.model.WorkCostCellDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer list/read model for a {@code WorkItem}.
 *
 * <p>The synthetic {@link #costCell} carries the FOR-04-19 work-catalog aggregation, collapsed to a
 * package-less shape by FOR-05-04 (Requirement 7.1): the single labour price plus the construction
 * and finishing material money ranges. It is a computed, never-persisted field assembled by the
 * service mapper via {@code WorkCatalogAggregationResolver} in an {@code @AfterMapping} (task
 * 6.2/14.2). Being attached onto the existing row, it never multiplies or splits the paginated
 * distinct-{@code WorkItem} rows (Requirement 5.3).
 *
 * <p>{@link #packages} is the set of offer packages this work is a MEMBER of — every package for
 * which a {@code WorkPackageOverride(workItem, offerPackage, member=true)} row exists. It is a
 * computed, never-persisted list of {@link RefDto} (id + localized package name), batch-populated by
 * {@code WorkItemService} after the generic list maps the page (one grouped
 * {@code findMembershipRefsByWorkItemIdIn} query for the whole page — no per-row N+1), so it never
 * multiplies or splits the paginated distinct-{@code WorkItem} rows.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkItemServiceModel {
    private Long id;
    private Long workCategoryId;
    private String workCategoryName;
    private Long unitId;
    private String unitName;
    private String name;
    private String code;
    private boolean active;

    /** The work item's three cost parts; {@code null} when the work item has no aggregation. */
    private WorkCostCellDto costCell;

    /** The offer packages this work is a member of (member=true); empty when it belongs to none. */
    private List<RefDto> packages = new ArrayList<>();
}
