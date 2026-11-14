package com.foremen.controller.model.staff;

import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.DecidedSide;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** FOR-11-02 (R4.5): one approval round as seen by staff (includes the deciding user id and on-behalf flag). */
public record StaffRoundDto(Long roundId, Integer roundNo, ApprovalRoundKind kind, ApprovalRoundStatus status,
                            LocalDate decisionDeadline, String managerNote, String suggestionLink,
                            String decisionComment, LocalDateTime decidedAt, Long decidedBy,
                            DecidedSide decidedSide, boolean onBehalf, Long sourceSuggestionId,
                            List<StaffOptionDto> options, Long chosenOptionId) {
}
