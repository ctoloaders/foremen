package com.foremen.controller.model;

import java.util.List;

/**
 * FOR-06-04 (Requirement 4.1; design §5.3) — the response envelope of the project-wide reconciled
 * purchases list ({@code GET /api/procurement-lines?projectId=&documentId=&matchStatus=&unplannedCategoryId=}).
 *
 * <p>Wraps the ordered {@link PurchaseLineDtoModel} rows, already project-filtered by the
 * {@code ProjectScopedService} plumbing and narrowed by any optional {@code documentId} /
 * {@code matchStatus} / {@code unplannedCategoryId} filters.
 *
 * @param items the reconciled purchase-line rows; never {@code null}, possibly empty
 */
public record PurchaseLineListResponseModel(
        List<PurchaseLineDtoModel> items) {
}
