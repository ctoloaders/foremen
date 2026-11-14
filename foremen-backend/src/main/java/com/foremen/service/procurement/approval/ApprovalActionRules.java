package com.foremen.service.procurement.approval;

import java.util.EnumSet;
import java.util.Set;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;

import static com.foremen.service.procurement.approval.ApprovalStageGuard.Result.OK;

/**
 * FOR-11-02 (R4.7, R14.4): pure computation of {@code allowedActions} for one position row.
 * Stage eligibility always comes from {@link ApprovalStageGuard}; this class only adds the
 * round state (open decision round, open suggestions) and the caller's role.
 *
 * <p>Client actions are offered to the {@code CLIENT} audience only (membership is checked by the
 * service). Staff write actions are offered to {@code ADMIN} and {@code MANAGER}; a {@code BUYER}
 * (and any other role) gets none.
 */
public final class ApprovalActionRules {

    private static final Set<String> STAFF_WRITE_ROLES = Set.of("ADMIN", "MANAGER");

    private ApprovalActionRules() {
    }

    /**
     * @param openRoundKind kind of the open decision round ({@code PROPOSAL}, {@code ANALOG},
     *                      {@code PACKAGE_CHOICE}); {@code null} or any other kind means none
     */
    public static Set<ClientAction> clientActions(Audience audience, SourcingPath path, PositionStatus status,
                                                  ApprovalRoundKind openRoundKind) {
        Set<ClientAction> result = EnumSet.noneOf(ClientAction.class);
        if (audience != Audience.CLIENT) {
            return result;
        }
        if (isDecisionRound(openRoundKind)) {
            if (ApprovalStageGuard.check(path, status, ApprovalOperation.ACCEPT) == OK) {
                result.add(ClientAction.ACCEPT);
            }
            if (ApprovalStageGuard.check(path, status, ApprovalOperation.REJECT) == OK) {
                result.add(ClientAction.REJECT);
            }
        }
        if (ApprovalStageGuard.check(path, status, ApprovalOperation.CLIENT_OWN) == OK) {
            result.add(ClientAction.CLIENT_OWN);
        }
        if (ApprovalStageGuard.check(path, status, ApprovalOperation.SUGGEST) == OK) {
            result.add(ClientAction.SUGGEST_LINK);
        }
        return result;
    }

    public static Set<StaffAction> staffActions(Audience audience, String roleCode, SourcingPath path,
                                                PositionStatus status, ApprovalRoundKind openRoundKind,
                                                int openSuggestions) {
        Set<StaffAction> result = EnumSet.noneOf(StaffAction.class);
        if (audience != Audience.STAFF_FULL || roleCode == null || !STAFF_WRITE_ROLES.contains(roleCode)) {
            return result;
        }
        boolean decisionOpen = isDecisionRound(openRoundKind);
        if (!decisionOpen) {
            if (ApprovalStageGuard.check(path, status, ApprovalOperation.SEND) == OK) {
                result.add(StaffAction.SEND);
            }
            if (ApprovalStageGuard.check(path, status, ApprovalOperation.SEND_CHOICE) == OK) {
                result.add(StaffAction.SEND_CHOICE);
            }
        }
        if (ApprovalStageGuard.check(path, status, ApprovalOperation.PROPOSE_ANALOG) == OK) {
            result.add(StaffAction.PROPOSE_ANALOG);
        }
        if (decisionOpen) {
            result.add(StaffAction.WITHDRAW);
            result.add(StaffAction.CHANGE_DEADLINE);
        }
        boolean canDecide = decisionOpen
                && (ApprovalStageGuard.check(path, status, ApprovalOperation.ACCEPT) == OK
                || ApprovalStageGuard.check(path, status, ApprovalOperation.REJECT) == OK);
        if (canDecide || ApprovalStageGuard.check(path, status, ApprovalOperation.CLIENT_OWN) == OK) {
            result.add(StaffAction.DECIDE_ON_BEHALF);
        }
        if (openSuggestions > 0) {
            result.add(StaffAction.RESOLVE_SUGGESTION);
        }
        return result;
    }

    private static boolean isDecisionRound(ApprovalRoundKind kind) {
        return kind == ApprovalRoundKind.PROPOSAL
                || kind == ApprovalRoundKind.ANALOG
                || kind == ApprovalRoundKind.PACKAGE_CHOICE;
    }
}
