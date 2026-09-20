package com.foremen.service.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.foremen.controller.model.WorkCatalogPackageCellDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer list/read model for a {@code WorkItem}.
 *
 * <p>The synthetic {@link #packagePivot} carries the FOR-04-19 work-catalog aggregation: per seeded
 * offer package (keyed by {@code offerPackage.id}) the THREE prices for that {@code (work, package)}
 * — the FOR-04-12b labour price plus the construction and finishing material money ranges. It is a
 * computed, never-persisted field assembled by the service mapper via
 * {@code WorkCatalogAggregationResolver} in an {@code @AfterMapping} (task 6.2), exactly as the
 * FOR-04-12b {@code WorkPrice} mapper attaches its {@code prices} map. Being attached onto the
 * existing row, it never multiplies or splits the paginated distinct-{@code WorkItem} rows
 * (Requirement 5.3).
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

    /** Per-package pivot keyed by {@code offerPackage.id}; empty when the work item has no aggregation. */
    private Map<Long, WorkCatalogPackageCellDto> packagePivot = new LinkedHashMap<>();
}
