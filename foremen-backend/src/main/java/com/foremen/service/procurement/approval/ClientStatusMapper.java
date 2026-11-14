package com.foremen.service.procurement.approval;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;

/**
 * Pure mapping {@code PositionStatus + SourcingPath + hasOpenRound -> ClientStatus} (FOR-11-02, R4.3).
 *
 * <p>Precedence: path {@code CLIENT_OWN} first, then an open round ({@code AWAITING_DECISION}), then the
 * position status. A visible position at {@code PLANNED} (or {@code PROPOSED} without an open round)
 * is {@code NOT_STARTED}.
 */
public final class ClientStatusMapper {

    private ClientStatusMapper() {
    }

    public static ClientStatus map(PositionStatus status, SourcingPath path, boolean hasOpenRound) {
        if (path == SourcingPath.CLIENT_OWN) {
            return ClientStatus.CLIENT_OWN;
        }
        if (hasOpenRound) {
            return ClientStatus.AWAITING_DECISION;
        }
        if (status == null) {
            return ClientStatus.NOT_STARTED;
        }
        return switch (status) {
            case PLANNED, PROPOSED -> ClientStatus.NOT_STARTED;
            case ACCEPTED -> ClientStatus.ACCEPTED;
            case IN_PROFORMA, CLIENT_PAID, WITH_BUYER, ORDERED, SUPPLIER_PAID -> ClientStatus.IN_PROGRESS;
            case IN_DELIVERY, PARTIALLY_RECEIVED -> ClientStatus.IN_DELIVERY;
            case RECEIVED -> ClientStatus.RECEIVED;
            case CANCELLED -> ClientStatus.CANCELLED;
        };
    }
}
