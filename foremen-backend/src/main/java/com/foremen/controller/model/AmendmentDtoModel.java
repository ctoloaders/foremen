package com.foremen.controller.model;

import java.time.LocalDate;

import com.foremen.dao.model.AmendmentStatus;

/**
 * FOR-06-08 (Requirement 1.1, 1.2; design §Data model, §Frontend) — list-row DTO for an
 * {@code EstimateAmendment}, the projection returned in the amendments list. Carries the id, the
 * owning {@code projectId}, the {@code seqNo}, the {@code status} ({@code Amendment_Status}, labeled
 * on the frontend via the {@code amendments.status.*} keys — the raw constant is never shown,
 * R11.1), the number of change lines ({@code lineCount}), the {@code effectiveDate} (unset in this
 * spec; set only by FOR-06-09, R7.4), and the free-text {@code note}.
 *
 * <p>The full per-line split lives on {@link AmendmentDtoExtendedModel}.
 */
public record AmendmentDtoModel(
        Long id,
        Long projectId,
        Integer seqNo,
        AmendmentStatus status,
        Integer lineCount,
        LocalDate effectiveDate,
        String note) {
}
