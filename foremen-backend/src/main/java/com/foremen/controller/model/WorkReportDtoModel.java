package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.WorkReportStatus;

/**
 * FOR-06-01 (Requirements 6.5, 11.1) — list-row DTO for a {@code WorkReport} (design §4.3), the
 * projection returned in the paginated reporting list. Carries the id, the project FK, the
 * work-item / worker / author FK ids with their localized display names (ru → pl → code), the
 * performed period, the status, and the revenue summary ({@code clientUnitPrice},
 * {@code totalQuantity}, {@code revenueValue}).
 *
 * <p>Per the revenue-only ownership boundary the cost side ({@code costValue}, {@code margin}) is
 * surfaced as explicit {@code null}s for forward compatibility (R6.5); the full per-room split and
 * media references live on {@link WorkReportDtoExtendedModel}.
 */
public record WorkReportDtoModel(
        Long id,
        Long projectId,
        Long workItemId,
        String workItemName,
        Long workerId,
        String workerName,
        Long authorId,
        String authorName,
        LocalDate performedFrom,
        LocalDate performedTo,
        WorkReportStatus status,
        BigDecimal clientUnitPrice,
        BigDecimal totalQuantity,
        BigDecimal revenueValue,
        BigDecimal costValue,
        BigDecimal margin) {
}
