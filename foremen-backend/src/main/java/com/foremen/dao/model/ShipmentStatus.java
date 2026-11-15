package com.foremen.dao.model;

/** FOR-11-04: normalised shipment status; {@code CANCELLED} is manual-only. Stored by name. */
public enum ShipmentStatus {
    CREATED(0),
    IN_TRANSIT(1),
    OUT_FOR_DELIVERY(2),
    DELIVERED(3),
    EXCEPTION(-1),
    UNKNOWN(-1),
    CANCELLED(-1);

    private final int rank;

    ShipmentStatus(int rank) {
        this.rank = rank;
    }

    /** {@code DELIVERED} and {@code CANCELLED} close the shipment. */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    /**
     * Forward-progress order {@code CREATED < IN_TRANSIT < OUT_FOR_DELIVERY < DELIVERED}.
     * {@code EXCEPTION}, {@code UNKNOWN} and {@code CANCELLED} have no rank and return {@code -1}.
     */
    public int rank() {
        return rank;
    }

    public boolean isRanked() {
        return rank >= 0;
    }
}
