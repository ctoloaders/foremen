package com.foremen.service.offer;

import java.math.BigDecimal;

/**
 * The offer readiness projection surfaced while an offer is on approval (FOR-05-07, Requirement
 * 17.7).
 *
 * <p>Confidentiality-safe: it combines the two readiness drivers — unresolved negotiation proposals
 * (open rounds) and unfilled finishing Placeholders — into a single {@code percentage}, plus the
 * underlying counts for display. It holds <b>no</b> cost, margin, worker rate, or estimate-internal
 * unit-price field (Property 21).
 *
 * @param percentage         the readiness percentage (100% exactly when there are no open rounds and
 *                           no unfilled placeholders)
 * @param openRounds         the number of open/unresolved negotiation rounds
 * @param totalRounds        the total number of negotiation rounds
 * @param unfilledPlaceholders the number of finishing Placeholders with no concrete product chosen
 * @param totalPlaceholders  the total number of finishing Placeholder slots
 */
public record OfferReadinessView(
        BigDecimal percentage,
        int openRounds,
        int totalRounds,
        int unfilledPlaceholders,
        int totalPlaceholders) {
}
