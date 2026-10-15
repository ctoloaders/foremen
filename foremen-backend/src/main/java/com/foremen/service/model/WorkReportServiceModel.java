package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.foremen.controller.model.WorkReportRoomModel;
import com.foremen.dao.model.WorkReportStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-01 (Requirement 6.5; design §4.3) — read-path service model for a {@code WorkReport},
 * satisfying the {@code ProjectScopedService}/{@code AdminService} CRUD contract
 * {@code WorkReportService} implements (entity-creation-rules step 4).
 *
 * <p>Carries the flat FK ids plus the <b>localized display names</b> ({@code workItemName},
 * {@code workerName}, {@code authorName}, {@code approvedByName}) resolved in the service mapper
 * with the request-locale rule (RU when the request locale language is {@code ru}, else PL) and a
 * {@code ru → pl → code} fallback (R12.1). The owned per-room split is projected into
 * {@code rooms}, each row carrying its localized {@code roomName}.
 *
 * <p><b>Revenue only (ownership boundary).</b> This spec owns the revenue side, so the model
 * exposes {@code clientUnitPrice}, the derived {@code totalQuantity} and {@code revenueValue}, and
 * the status/decision fields. The cost side ({@code workerUnitPrice}, {@code costValue},
 * {@code margin}) is FOR-06-10/11/06 and is NOT computed here; {@code costValue}/{@code margin} are
 * exposed as explicit {@code null}s for forward compatibility (R6.5). {@code mediaRefs} carries the
 * report's media references (R8.2); it is empty until the media binding ships (design §6).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkReportServiceModel {
    private Long id;

    private Long projectId;

    private Long workItemId;
    /** Localized work-item display name (ru → pl → code). */
    private String workItemName;

    private Long workerId;
    /** The worker's display name. */
    private String workerName;

    private Long authorId;
    /** The author's display name. */
    private String authorName;

    private LocalDate performedFrom;
    private LocalDate performedTo;

    private WorkReportStatus status;

    private Long approvedById;
    /** The approver's display name (null until decided). */
    private String approvedByName;
    private OffsetDateTime approvedAt;
    private String rejectionReason;

    /** Frozen revenue unit price (R6). */
    private BigDecimal clientUnitPrice;
    /** Derived {@code Σ rooms.quantity} (R1.4). */
    private BigDecimal totalQuantity;
    /** Derived {@code round2(clientUnitPrice × totalQuantity)} (R1.5). */
    private BigDecimal revenueValue;

    /**
     * Forward-compat cost placeholders — always {@code null} here; the cost side is FOR-06-10/11/06
     * (R6.5). Present so the read model's shape is stable when payroll/margin later populate them.
     */
    private BigDecimal costValue;
    private BigDecimal margin;

    private String note;

    /** The per-room quantity split (R1.2), each row with its localized {@code roomName}. */
    private List<WorkReportRoomModel> rooms = new ArrayList<>();

    /** Media references for the report (R8.2); empty until the media binding ships (design §6). */
    private List<String> mediaRefs = new ArrayList<>();
}
