package com.foremen.service.offer;

/**
 * FOR-05-07 additional tasks — what an estimate change does to the project's offer.
 */
public enum OfferEstimateImpact {
    /** No offer is affected (no offer, a DRAFT that live-tracks the estimate, or a closed one). */
    NONE,
    /** The client-visible, not-yet-approved offer is withdrawn. */
    WITHDRAW,
    /** The approved offer is superseded by a new DRAFT version (revision + 1). */
    REVISE
}
