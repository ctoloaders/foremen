package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.foremen.dao.model.AmendmentStatus;

/**
 * FOR-06-08 (Requirements 1.4, 4.3; design §Data model, §Frontend) — detail DTO for a single
 * {@code EstimateAmendment}, the projection returned for a single-amendment read. Extends the list
 * row with the resolved change lines ({@code lines}, each carrying its {@code changeType}, subject,
 * delta, and — for cost lines — volume / room / unit price with localized names, R1.4) and the
 * informational aggregate {@code totalAddedValue} = {@code Σ deltaQuantity × extraUnitPrice} over the
 * cost-bearing lines (R4.3).
 *
 * <p>The {@code status} ({@code Amendment_Status}) is labeled on the frontend (R11.1); the aggregate
 * is informational and is NOT applied to any downstream data in this spec.
 *
 * <p>FOR-06-09 (R2.5, R6.3) extends the read model with the linked {@code AMENDMENT} document id
 * ({@code signableDocumentId}), the read-through signing progress ({@code signingProgress}:
 * signed/total + outstanding signers, never stored), and surfaces the {@code effectiveDate} once set
 * — so the Aneksy tab renders the signing surface without a second lifecycle.
 *
 * @param id                 the amendment id
 * @param projectId          the owning project FK
 * @param seqNo              1..N per project
 * @param status             the {@code Amendment_Status} (labeled on the frontend)
 * @param signableDocumentId the linked {@code AMENDMENT} document id once sent for signing, else {@code null} (FOR-06-09 R2.5)
 * @param signingProgress    the read-through signing progress of the linked document, else {@code null} (FOR-06-09 R2.5, R6.2)
 * @param effectiveDate      the all-parties-signed date; set by FOR-06-09 on signing (R3.1, R7.4)
 * @param note               free text, untranslated
 * @param lines              the resolved change lines (R1.4)
 * @param totalAddedValue    informational {@code Σ deltaQuantity × extraUnitPrice} (R4.3)
 */
public record AmendmentDtoExtendedModel(
        Long id,
        Long projectId,
        Integer seqNo,
        AmendmentStatus status,
        Long signableDocumentId,
        AmendmentSigningProgressDto signingProgress,
        LocalDate effectiveDate,
        String note,
        List<AmendmentLineDto> lines,
        BigDecimal totalAddedValue) {
}
