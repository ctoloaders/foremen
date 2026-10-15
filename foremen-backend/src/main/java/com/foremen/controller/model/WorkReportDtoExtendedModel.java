package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.WorkReportStatus;

/**
 * FOR-06-01 (Requirements 6.5, 11.1, 11.3) — detail DTO for a single {@code WorkReport} (design
 * §4.3), the projection returned for a single-report read / review view. Extends the list row with
 * the full decision trail ({@code approvedById}/{@code approvedByName}/{@code approvedAt}/
 * {@code rejectionReason}), the free-form {@code note}, the resolved per-room split
 * ({@code rooms}), and the media references ({@code mediaRefs}).
 *
 * <p>Each referenced name is the localized display name (ru → pl → code/label, R12.1). Per the
 * revenue-only ownership boundary the cost side ({@code costValue}, {@code margin}) is surfaced as
 * explicit {@code null}s for forward compatibility (R6.5).
 */
public record WorkReportDtoExtendedModel(
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
        Long approvedById,
        String approvedByName,
        OffsetDateTime approvedAt,
        String rejectionReason,
        BigDecimal clientUnitPrice,
        BigDecimal totalQuantity,
        BigDecimal revenueValue,
        BigDecimal costValue,
        BigDecimal margin,
        String note,
        List<WorkReportRoomModel> rooms,
        List<String> mediaRefs) {
}
