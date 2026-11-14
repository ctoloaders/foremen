package com.foremen.service.procurement;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.RequestStatus;

import java.util.Collection;

/**
 * FOR-11-00 (R11.8): pure derivation of a purchase request status from its positions' stages.
 *
 * <p>No positions -> OPEN; all CANCELLED -> CANCELLED; all non-cancelled RECEIVED -> COMPLETED; all
 * non-cancelled at WITH_BUYER or later -> RELEASED; otherwise OPEN.
 */
public final class PurchaseRequestStatusDeriver {

    private PurchaseRequestStatusDeriver() {
    }

    public static RequestStatus derive(Collection<PositionStatus> positions) {
        if (positions == null || positions.isEmpty()) {
            return RequestStatus.OPEN;
        }
        boolean anyActive = false;
        boolean allReceived = true;
        boolean allReleased = true;
        for (PositionStatus s : positions) {
            if (s == PositionStatus.CANCELLED) {
                continue;
            }
            anyActive = true;
            if (s != PositionStatus.RECEIVED) {
                allReceived = false;
            }
            // Enum order is the lifecycle order; CANCELLED (last) is skipped above.
            if (s.ordinal() < PositionStatus.WITH_BUYER.ordinal()) {
                allReleased = false;
            }
        }
        if (!anyActive) {
            return RequestStatus.CANCELLED;
        }
        if (allReceived) {
            return RequestStatus.COMPLETED;
        }
        return allReleased ? RequestStatus.RELEASED : RequestStatus.OPEN;
    }
}
