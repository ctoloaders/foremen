package com.foremen.service.procurement.approval;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** FOR-11-02 (design 4): round projection for the staff view (adds the deciding user id and the resolved suggestion). */
public record StaffRoundSource(Long roundId, Long positionId, Integer roundNo, ApprovalRoundKind kind,
                               ApprovalRoundStatus status, LocalDate decisionDeadline, String managerNote,
                               String suggestionLink, String decisionComment, LocalDateTime decidedAt,
                               Long decidedById, DecidedSide decidedSide, boolean onBehalf,
                               Long sourceSuggestionId, Long chosenOptionId) {
}
