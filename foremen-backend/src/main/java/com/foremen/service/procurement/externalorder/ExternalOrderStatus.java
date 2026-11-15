package com.foremen.service.procurement.externalorder;

/** FOR-11-03 (R11.4): the lifecycle stage of an external (supplier-side) order, stored by name. */
public enum ExternalOrderStatus {
    DRAFT,
    SENT_FOR_PAYMENT,
    PAID,
    CANCELLED;

    /** A committed order counts towards position coverage and stage (R13.2, R17.1). */
    public boolean isCommitted() {
        return this == SENT_FOR_PAYMENT || this == PAID;
    }
}
