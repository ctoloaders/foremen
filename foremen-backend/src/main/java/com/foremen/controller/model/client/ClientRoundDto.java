package com.foremen.controller.model.client;

import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.DecidedSide;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** FOR-11-02: an approval round as the client sees it. */
public record ClientRoundDto(Long roundId, Integer roundNo, ApprovalRoundKind kind, ApprovalRoundStatus status,
                             LocalDate decisionDeadline, String managerNote, String suggestionLink,
                             String decisionComment, LocalDateTime decidedAt, DecidedSide decidedSide,
                             boolean onBehalf, List<ClientOptionDto> options, Long chosenOptionId) {
}
