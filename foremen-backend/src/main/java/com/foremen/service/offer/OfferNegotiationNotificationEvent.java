package com.foremen.service.offer;

/**
 * Immutable application event carrying an offer-negotiation state change that should notify the
 * other side of the negotiation (FOR-05-07, Requirement 14; design §OfferNotificationEmitter).
 *
 * <p>Published by {@link NegotiationService} at the two concrete notification triggers of
 * Requirement 14:
 * <ul>
 *   <li>a client discount request / new round ⇒ notify the project MANAGER
 *       ({@link Trigger#DISCOUNT_REQUEST_TO_MANAGER}, R14.1);</li>
 *   <li>a manager proposal ⇒ notify the requesting CLIENT
 *       ({@link Trigger#MANAGER_PROPOSAL_TO_CLIENT}, R14.2);</li>
 *   <li>a manager rejection ⇒ notify the requesting CLIENT
 *       ({@link Trigger#MANAGER_REJECT_TO_CLIENT}, R14.3).</li>
 * </ul>
 *
 * <p>Following the repo's {@code InvitationEmailEvent} convention, this is a plain immutable record
 * published through {@code ApplicationEventPublisher}. {@link OfferNotificationEmitter} consumes it
 * on {@code @TransactionalEventListener(AFTER_COMMIT)} so the notification emission runs only
 * <em>after</em> the negotiation transition has durably committed and a failure while emitting can
 * never roll back that transition (R14.4 / R14.5 / R10.11). The event carries only ids/paths (never
 * a loaded entity), so it stays valid on the after-commit thread.
 *
 * <p>The event carries the <em>requesting client</em> user id ({@link #requestingClientUserId}) for
 * the client-facing triggers (the {@code createdBy} of the request round the manager answered); the
 * MANAGER recipient for the {@link Trigger#DISCOUNT_REQUEST_TO_MANAGER} trigger is resolved by the
 * emitter from the project's members (it is not known cheaply at publish time).
 *
 * @param trigger                which side to notify and with which notification-type variant
 * @param offerId                the offer whose negotiation changed
 * @param projectId              the owning project id (used to resolve the MANAGER recipient and the
 *                               Offer_Tab deep-link)
 * @param requestingClientUserId the requesting client's user id for the client-facing triggers
 *                               ({@code createdBy} of the answered request round); {@code null} for
 *                               the manager-facing trigger
 */
public record OfferNegotiationNotificationEvent(
        Trigger trigger,
        Long offerId,
        Long projectId,
        Long requestingClientUserId) {

    /** The concrete Requirement 14 notification triggers this spec wires. */
    public enum Trigger {
        /** A client discount request / new round ⇒ notify the project MANAGER (R14.1). */
        DISCOUNT_REQUEST_TO_MANAGER,
        /** A manager proposal ⇒ notify the requesting CLIENT (R14.2). */
        MANAGER_PROPOSAL_TO_CLIENT,
        /** A manager rejection ⇒ notify the requesting CLIENT (R14.3). */
        MANAGER_REJECT_TO_CLIENT
    }
}
