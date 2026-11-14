package com.foremen.service.model;

import java.time.LocalDate;

import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-11-02: minimal service model of an approval round. It only satisfies the
 * {@code ProjectScopedService} plumbing; client and staff views use their own DTO trees (no price here).
 */
@Getter
@Setter
@NoArgsConstructor
public class PositionApprovalRoundServiceModel {
    private Long id;
    private Long positionId;
    private Integer roundNo;
    private ApprovalRoundKind kind;
    private ApprovalRoundStatus status;
    private LocalDate decisionDeadline;
}
