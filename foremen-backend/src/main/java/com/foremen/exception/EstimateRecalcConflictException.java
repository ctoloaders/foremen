package com.foremen.exception;

import com.foremen.service.estimate.recalc.EstimateImpact;

/**
 * FOR-05-05 additional task 3 — a room save without {@code force} would change the project's
 * estimate. Thrown from inside the room-save transaction, so every room write AND the estimate
 * derivation roll back (the dry run persists nothing). Rendered as HTTP 409 with the localized
 * {@link com.foremen.exception.dto.EstimateRecalcConflictError} body; the client shows the changes
 * and, on approval, re-sends the identical request with {@code force=true}.
 */
public class EstimateRecalcConflictException extends RuntimeException {

    private final transient EstimateImpact impact;
    private final String offerImpact;

    public EstimateRecalcConflictException(EstimateImpact impact) {
        this(impact, null);
    }

    /**
     * @param impact      the estimate changes
     * @param offerImpact what approving does to the project's offer (FOR-05-07: {@code NONE} /
     *                    {@code WITHDRAW} / {@code REVISE}), or {@code null} when unknown
     */
    public EstimateRecalcConflictException(EstimateImpact impact, String offerImpact) {
        super("error.estimate.recalc.conflict");
        this.impact = impact;
        this.offerImpact = offerImpact;
    }

    public EstimateImpact getImpact() {
        return impact;
    }

    public String getOfferImpact() {
        return offerImpact;
    }
}
