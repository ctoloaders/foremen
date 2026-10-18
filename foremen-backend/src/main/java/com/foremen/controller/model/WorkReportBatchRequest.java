package com.foremen.controller.model;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/**
 * FOR-06-01 Amendment A1 (Requirement 16; design A1.2) — bulk-create payload for the matrix batch
 * authoring surface: a non-empty list of the existing single {@link WorkReportCreateRequest} items
 * (R2.1 shape), submitted as one all-or-nothing batch to {@code POST /api/work-reports/batch}.
 *
 * <p>Each item is validated with the same {@code @Valid} contract as the single create path, and
 * {@code WorkReportService.createBatch(...)} runs the whole list in one transaction: any item that
 * fails validation aborts (rolls back) the entire batch and surfaces as a {@code 400} identifying
 * the failing item by index (R16.4).
 *
 * @param reports the per-report create payloads to persist as one batch (at least one)
 */
public record WorkReportBatchRequest(
        @NotEmpty @Valid List<WorkReportCreateRequest> reports) {
}
