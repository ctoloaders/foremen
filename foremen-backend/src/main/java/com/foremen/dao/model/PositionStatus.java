package com.foremen.dao.model;

/** FOR-11-00 (R8.1): the position stage, stored by name. Transitions are owned by the transition policy. */
public enum PositionStatus {
    PLANNED,
    PROPOSED,
    ACCEPTED,
    IN_PROFORMA,
    CLIENT_PAID,
    WITH_BUYER,
    ORDERED,
    SUPPLIER_PAID,
    IN_DELIVERY,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED
}
