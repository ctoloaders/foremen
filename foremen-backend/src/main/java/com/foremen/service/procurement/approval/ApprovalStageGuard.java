package com.foremen.service.procurement.approval;

import java.util.EnumSet;
import java.util.Set;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;

import static com.foremen.dao.model.PositionStatus.*;

/**
 * FOR-11-02 (R14.4): the single stage matrix of the approval workflow, and the seam for FOR-06-08.
 * From IN_PROFORMA on every operation is {@link Result#STAGE_NOT_ALLOWED}; only an amendment may
 * change such a position.
 *
 * <pre>
 * SEND                   AGREED   PLANNED
 * SEND_CHOICE            PACKAGE  PLANNED
 * PROPOSE_ANALOG         AGREED   PROPOSED, ACCEPTED
 * SUGGEST, CLIENT_OWN    AGREED   PROPOSED, ACCEPTED
 * ACCEPT, REJECT         AGREED   PROPOSED;  PACKAGE: PLANNED
 * </pre>
 */
public final class ApprovalStageGuard {

    public enum Result {
        OK,
        STAGE_NOT_ALLOWED
    }

    private ApprovalStageGuard() {
    }

    public static Result check(SourcingPath path, PositionStatus status, ApprovalOperation operation) {
        return allowedStages(path, operation).contains(status) ? Result.OK : Result.STAGE_NOT_ALLOWED;
    }

    private static Set<PositionStatus> allowedStages(SourcingPath path, ApprovalOperation operation) {
        if (path == null || operation == null) {
            return none();
        }
        return switch (operation) {
            case SEND -> path == SourcingPath.AGREED ? EnumSet.of(PLANNED) : none();
            case SEND_CHOICE -> path == SourcingPath.PACKAGE ? EnumSet.of(PLANNED) : none();
            case PROPOSE_ANALOG, SUGGEST, CLIENT_OWN ->
                    path == SourcingPath.AGREED ? EnumSet.of(PROPOSED, ACCEPTED) : none();
            case ACCEPT, REJECT -> switch (path) {
                case AGREED -> EnumSet.of(PROPOSED);
                case PACKAGE -> EnumSet.of(PLANNED);
                case CLIENT_OWN -> none();
            };
        };
    }

    private static Set<PositionStatus> none() {
        return EnumSet.noneOf(PositionStatus.class);
    }
}
