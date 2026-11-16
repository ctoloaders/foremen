package com.foremen.service.clientpayment;

/** FOR-11-06 (R1.9): the derived, kind-independent status shown to users. */
public enum DisplayStatus {
    DRAFT,
    PLANNED,
    DUE,
    ISSUED,
    PARTIALLY_PAID,
    PAID,
    OVERDUE,
    CANCELLED
}
