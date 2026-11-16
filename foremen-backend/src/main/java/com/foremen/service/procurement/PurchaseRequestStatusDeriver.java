package com.foremen.service.procurement;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.RequestStatus;
import java.util.Collection;

/**
 * FOR-11-00 (R11.8): pure derivation of a purchase request status from its positions' stages.
 *
 * <p>No positions -> OPEN; all CANCELLED -> CANCELLED; all non-cancelled {@code Delivery_Complete} ->
 * COMPLETED (FOR-11-06 R10.4: RECEIVED and not client-unpaid); all non-cancelled at WITH_BUYER or
 * later -> RELEASED; otherwise OPEN.
 */
public final class PurchaseRequestStatusDeriver {

    /** A position's stage plus its {@code client_unpaid} flag. */
    public record Stage(PositionStatus status, boolean clientUnpaid) {
    }

    private PurchaseRequestStatusDeriver() {
    }

    /** Derivation for positions that are not client-unpaid. */
    public static RequestStatus derive(Collection<PositionStatus> positions) {
        if (positions == null) {
            return RequestStatus.OPEN;
        }
        return deriveStages(positions.stream().map(s -> new Stage(s, false)).toList());
    }

    public static RequestStatus deriveStages(Collection<Stage> positions) {
        if (positions == null || positions.isEmpty()) {
            return RequestStatus.OPEN;
        }
        boolean anyActive = false;
        boolean allComplete = true;
        boolean allReleased = true;
        for (Stage stage : positions) {
            PositionStatus s = stage.status();
            if (s == PositionStatus.CANCELLED) {
                continue;
            }
            anyActive = true;
            if (!PositionDelivery.isComplete(s, stage.clientUnpaid())) {
                allComplete = false;
            }
            // Enum order is the lifecycle order; CANCELLED (last) is skipped above.
            if (s.ordinal() < PositionStatus.WITH_BUYER.ordinal()) {
                allReleased = false;
            }
        }
        if (!anyActive) {
            return RequestStatus.CANCELLED;
        }
        if (allComplete) {
            return RequestStatus.COMPLETED;
        }
        return allReleased ? RequestStatus.RELEASED : RequestStatus.OPEN;
    }
}
