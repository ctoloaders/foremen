package com.foremen.service.procurement.approval;

import java.util.EnumSet;
import java.util.Set;

import static com.foremen.service.procurement.approval.ApprovalRoundStatus.*;

/**
 * FOR-11-02 (R2.4): pure definition of the legal round status changes.
 * PROPOSAL, ANALOG, PACKAGE_CHOICE: OPEN to ACCEPTED, REJECTED, SUPERSEDED or WITHDRAWN.
 * CLIENT_SUGGESTION: OPEN to RESOLVED or DECLINED. CLIENT_OWN (born DECLARED) has no exit.
 * Every other status is terminal.
 */
public final class ApprovalRoundStatusMachine {

    private ApprovalRoundStatusMachine() {
    }

    public static Set<ApprovalRoundStatus> exits(ApprovalRoundKind kind, ApprovalRoundStatus status) {
        if (kind == null || status != OPEN) {
            return EnumSet.noneOf(ApprovalRoundStatus.class);
        }
        return switch (kind) {
            case PROPOSAL, ANALOG, PACKAGE_CHOICE -> EnumSet.of(ACCEPTED, REJECTED, SUPERSEDED, WITHDRAWN);
            case CLIENT_SUGGESTION -> EnumSet.of(RESOLVED, DECLINED);
            case CLIENT_OWN -> EnumSet.noneOf(ApprovalRoundStatus.class);
        };
    }

    public static boolean canMove(ApprovalRoundKind kind, ApprovalRoundStatus from, ApprovalRoundStatus to) {
        return to != null && exits(kind, from).contains(to);
    }

    public static boolean isTerminal(ApprovalRoundKind kind, ApprovalRoundStatus status) {
        return exits(kind, status).isEmpty();
    }
}
