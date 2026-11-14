package com.foremen.service.procurement.approval;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** FOR-11-02 (design 4): narrowed projection of a round for the client view (no internal prices, no user ids). */
public record ClientRoundSource(Long roundId, Long positionId, Integer roundNo, ApprovalRoundKind kind,
                                ApprovalRoundStatus status, LocalDate decisionDeadline, String managerNote,
                                String suggestionLink, String decisionComment, LocalDateTime decidedAt,
                                DecidedSide decidedSide, boolean onBehalf, Long chosenOptionId) {
}
