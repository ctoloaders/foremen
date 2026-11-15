package com.foremen.service.procurement.proforma;

/** FOR-11-03 (R2.3): lifecycle status of a client proforma, stored as VARCHAR. */
public enum ProformaStatus {
    DRAFT,
    ISSUED,
    PARTIALLY_PAID,
    PAID,
    CANCELLED
}
