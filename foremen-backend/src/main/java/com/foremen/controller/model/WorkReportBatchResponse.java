package com.foremen.controller.model;

import java.util.List;

/**
 * FOR-06-01 Amendment A1 (Requirement 16; design A1.2) — success response for a bulk create: the
 * ids of the reports persisted by {@code POST /api/work-reports/batch}, in submission order.
 *
 * <p>Returned with {@code 200} only when every item of the batch committed (R16.4, all-or-nothing);
 * a single failing item yields a {@code 400} {@code WorkReportBatchItemError} instead of this body.
 * The rich per-report read projection (localized names, status, derived revenue) is fetched via
 * {@code GET /api/work-reports/{id}} as for the single create path.
 *
 * @param created the ids of the persisted reports, aligned to the submitted {@code reports} order
 */
public record WorkReportBatchResponse(List<Long> created) {
}
