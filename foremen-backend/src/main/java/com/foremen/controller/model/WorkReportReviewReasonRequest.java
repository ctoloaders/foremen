package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

/**
 * FOR-06-01 (Requirements 4.3, 4.4; design §4.2) — the request body carrying the mandatory reason
 * for the {@code POST /api/work-reports/{id}/reject} and {@code POST /api/work-reports/{id}/request-changes}
 * review transitions. Both transitions require a non-empty reason recorded on the report
 * ({@code error.workReport.rejection.reasonRequired}); the {@code approve} transition takes no body.
 */
public record WorkReportReviewReasonRequest(
        @NotBlank String reason) {
}
