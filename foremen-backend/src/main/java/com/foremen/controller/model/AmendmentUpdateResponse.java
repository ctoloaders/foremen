package com.foremen.controller.model;

import java.time.LocalDate;

import com.foremen.dao.model.AmendmentStatus;

/**
 * FOR-06-08 (design §Backend components) — update response for an {@code EstimateAmendment}, mapped
 * from the persisted {@code AmendmentServiceExtendedModel}: echoes the {@code id}, the owning
 * {@code projectId}, the {@code seqNo}, the {@code status}, the {@code effectiveDate} (null in this
 * spec, R7.4), and the {@code note}. The rich read projection (change lines, aggregate) is fetched
 * via {@code GET /api/amendments/{id}}.
 */
public record AmendmentUpdateResponse(
        Long id,
        Long projectId,
        Integer seqNo,
        AmendmentStatus status,
        LocalDate effectiveDate,
        String note) {
}
