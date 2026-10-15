package com.foremen.controller.model;

import java.time.LocalDate;
import java.util.List;

/**
 * FOR-06-01 (design §4.3) — create response for a {@code WorkReport}, mapped from the persisted
 * {@code WorkReportServiceExtendedModel}: echoes the new {@code id} and the author-supplied inputs
 * ({@code projectId}, {@code workItemId}, {@code workerId}, the performed interval, {@code note},
 * the per-room rows, and the media references). The rich read projection (localized names, status,
 * derived revenue) is fetched via {@code GET /api/work-reports/{id}}.
 */
public record WorkReportCreateResponse(
        Long id,
        Long projectId,
        Long workItemId,
        Long workerId,
        LocalDate performedFrom,
        LocalDate performedTo,
        String note,
        List<WorkReportRoomModel> rooms,
        List<String> mediaRefs) {
}
