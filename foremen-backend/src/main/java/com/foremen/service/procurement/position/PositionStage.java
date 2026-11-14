package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;

import java.util.EnumMap;
import java.util.Map;

/**
 * FOR-11-00 (R8.8, R12): single mapping from a position stage to its seeded {@code delivery_statuses}
 * code (changeset 228) and to its funnel rank. {@code CANCELLED} is unranked.
 */
public final class PositionStage {

    /** Rank returned for {@link PositionStatus#CANCELLED}. */
    public static final int UNRANKED = -1;

    private static final Map<PositionStatus, String> CODES = new EnumMap<>(PositionStatus.class);

    static {
        CODES.put(PositionStatus.PLANNED, "new");
        CODES.put(PositionStatus.PROPOSED, "proposed");
        CODES.put(PositionStatus.ACCEPTED, "accepted");
        CODES.put(PositionStatus.IN_PROFORMA, "in_proforma");
        CODES.put(PositionStatus.CLIENT_PAID, "client_paid");
        CODES.put(PositionStatus.WITH_BUYER, "with_buyer");
        CODES.put(PositionStatus.ORDERED, "ordered");
        CODES.put(PositionStatus.SUPPLIER_PAID, "supplier_paid");
        CODES.put(PositionStatus.IN_DELIVERY, "in_delivery");
        CODES.put(PositionStatus.PARTIALLY_RECEIVED, "partially_received");
        CODES.put(PositionStatus.RECEIVED, "delivered");
        CODES.put(PositionStatus.CANCELLED, "cancelled");
    }

    private PositionStage() {
    }

    /** The {@code delivery_statuses.code} for the stage. */
    public static String statusCode(PositionStatus status) {
        return CODES.get(status);
    }

    /** Funnel position (1-based, equals the seeded {@code order_no}); {@link #UNRANKED} for CANCELLED. */
    public static int rank(PositionStatus status) {
        return status == PositionStatus.CANCELLED ? UNRANKED : status.ordinal() + 1;
    }
}
