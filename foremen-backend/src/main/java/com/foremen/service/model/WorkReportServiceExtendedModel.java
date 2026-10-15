package com.foremen.service.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.foremen.controller.model.WorkReportRoomModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-01 (Requirement 6.5; design §4.3) — write-path service model for a {@code WorkReport},
 * satisfying the {@code ProjectScopedService}/{@code AdminService} CRUD contract
 * {@code WorkReportService} implements (entity-creation-rules step 4).
 *
 * <p>Carries only the <b>author-supplied</b> inputs the service validates and resolves: the
 * {@code projectId}, {@code workItemId}, and {@code workerId} FKs, the
 * {@code [performedFrom, performedTo]} interval, the free-form {@code note}, the per-room rows, and
 * the {@code mediaRefs}. The {@code author} is the authenticated caller, the status/decision fields
 * and the revenue valuation ({@code clientUnitPrice}/{@code totalQuantity}/{@code revenueValue}) are
 * all <b>derived server-side</b> by {@code WorkReportService} (R1.4, R1.5, R4, R5, R6) and are
 * therefore absent here. The service also resolves each PERCENT row to an absolute quantity and
 * snapshots {@code planQuantity} (R2.2, R2.6), so a write-path {@link WorkReportRoomModel} supplies
 * {@code roomId}, {@code quantity}, and {@code inputMode} only.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkReportServiceExtendedModel {
    private Long id;

    private Long projectId;
    private Long workItemId;
    private Long workerId;

    private LocalDate performedFrom;
    private LocalDate performedTo;

    private String note;

    /** The per-room rows the author submitted ({@code roomId}, {@code quantity}, {@code inputMode}). */
    private List<WorkReportRoomModel> rooms = new ArrayList<>();

    /** Media references attached while composing/editing (R8.1); the service binds them (design §6). */
    private List<String> mediaRefs = new ArrayList<>();
}
