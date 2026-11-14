package com.foremen.service.procurement.approval;

/** FOR-11-02 (R4.3): the coarse position status shown to the client. */
public enum ClientStatus {
    NOT_STARTED,
    AWAITING_DECISION,
    ACCEPTED,
    IN_PROGRESS,
    IN_DELIVERY,
    RECEIVED,
    CANCELLED,
    CLIENT_OWN
}
