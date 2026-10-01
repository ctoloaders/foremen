package com.foremen.dao.model;

/**
 * A transition-driving action on an {@code Offer} (FOR-05-07, Requirement 3).
 *
 * <p>Each action names a verb the {@code OfferStatusMachine} maps onto a legal
 * {@link OfferStatus} transition together with the acting role:
 *
 * <ul>
 *   <li>{@link #SEND} — an executor (MANAGER/ADMIN) sends a {@code DRAFT} offer to the client
 *       ({@code DRAFT → SENT}, R3.2).</li>
 *   <li>{@link #REQUEST_CHANGES} — the CLIENT opens a discount request on a {@code SENT}/{@code
 *       COUNTERED} offer ({@code → CHANGES_REQUESTED}, R3.3).</li>
 *   <li>{@link #PROPOSE} — the executor answers a {@code CHANGES_REQUESTED} offer with a manager
 *       proposal ({@code CHANGES_REQUESTED → COUNTERED}, R3.4).</li>
 *   <li>{@link #APPROVE} — the CLIENT approves a {@code SENT}/{@code COUNTERED} offer
 *       ({@code → APPROVED}, R3.5).</li>
 *   <li>{@link #REJECT} — the CLIENT rejects a {@code SENT}/{@code COUNTERED} offer
 *       ({@code → REJECTED}, R3.6).</li>
 *   <li>{@link #WITHDRAW} — an executor withdraws any non-terminal offer ({@code → WITHDRAWN},
 *       R3.7).</li>
 * </ul>
 *
 * <p>This is a pure domain verb; it carries no localized label because it is never surfaced to the
 * user (the user sees {@link OfferStatus} / {@link NegotiationRoundKind} labels).
 */
public enum OfferAction {
    SEND,
    REQUEST_CHANGES,
    PROPOSE,
    APPROVE,
    REJECT,
    WITHDRAW
}
