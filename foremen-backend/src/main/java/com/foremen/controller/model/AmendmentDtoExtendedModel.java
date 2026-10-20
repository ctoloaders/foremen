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
 * @param id               the amendment id
 * @param projectId        the owning project FK
 * @param seqNo            1..N per project
 * @param status           the {@code Amendment_Status} (labeled on the frontend)
 * @param effectiveDate    unset in this spec; set only by FOR-06-09 (R7.4)
 * @param note             free text, untranslated
 * @param lines            the resolved change lines (R1.4)
 * @param totalAddedValue  informational {@code Σ deltaQuantity × extraUnitPrice} (R4.3)
 */
public record AmendmentDtoExtendedModel(
        Long id,
        Long projectId,
        Integer seqNo,
        AmendmentStatus status,
        LocalDate effectiveDate,
        String note,
        List<AmendmentLineDto> lines,
        BigDecimal totalAddedValue) {
}
