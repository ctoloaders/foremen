package com.foremen.service.offer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.OfferVisibilityStatus;
import com.foremen.exception.ForemenApiException;

/**
 * Derives the client-facing {@link OfferVisibilityStatus} projection of an {@link OfferStatus} and
 * enforces the manager-gated client-visibility gate (FOR-05-07, Requirements 5.9, 17.4, 17.5,
 * 17.9).
 *
 * <p>{@code Offer_Visibility_Status} is <b>not</b> an independent state machine: it is a pure,
 * total, deterministic projection <b>of</b> {@link OfferStatus} per the Requirement 17.4 mapping:
 * <ul>
 *   <li>{@link OfferStatus#DRAFT} &rarr; {@link OfferVisibilityStatus#DRAFT} (not client-visible);</li>
 *   <li>{@link OfferStatus#SENT} / {@link OfferStatus#CHANGES_REQUESTED} /
 *       {@link OfferStatus#COUNTERED} &rarr; {@link OfferVisibilityStatus#ON_APPROVAL} (the
 *       client-visible negotiable window);</li>
 *   <li>{@link OfferStatus#APPROVED} &rarr; {@link OfferVisibilityStatus#APPROVED} (agreed, ready to
 *       sign);</li>
 *   <li>the terminal {@link OfferStatus#REJECTED} / {@link OfferStatus#WITHDRAWN} &rarr;
 *       {@link OfferVisibilityStatus#CLOSED} (a dead offer, still readable by the owning client as
 *       the terminal result of a reject/withdraw, but outside the negotiable window).</li>
 * </ul>
 *
 * <p>The projection is <b>total</b> over every {@link OfferStatus} value: the terminal statuses
 * {@link OfferStatus#REJECTED} and {@link OfferStatus#WITHDRAWN} map to
 * {@link OfferVisibilityStatus#CLOSED} rather than throwing, so a client that rejects or withdraws
 * an offer can still read the terminal result through {@code ClientOfferReadModelAssembler} (which
 * always projects a visibility). This keeps the resolver a faithful, total projection of all offer
 * states.
 *
 * <p>{@link #assertClientVisible(OfferStatus, Object)} is the server-side client-visibility gate: a
 * CLIENT reaching an offer whose visibility is {@link OfferVisibilityStatus#DRAFT} is denied with a
 * {@code 404 error.entity.not.found} — indistinguishable from a missing entity, matching the
 * {@code ProjectScopedService} {@code Access_Denied_Outcome} convention so a not-yet-sent offer is
 * not revealed to the client (Requirements 5.9, 17.5).
 *
 * <p>The resolver is a stateless {@code @Component} performing no I/O, mirroring the sibling
 * {@link DiscountResolver} pure-collaborator convention so it is exercised directly by
 * property-based tests without persistence.
 */
@Component
public class OfferVisibilityResolver {

    /** Message code for the {@code Access_Denied_Outcome}: 404, indistinguishable from missing. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /**
     * Projects an {@link OfferStatus} onto its client-facing {@link OfferVisibilityStatus} per the
     * Requirement 17.4 mapping.
     *
     * @param offerStatus the offer's lifecycle status; must be non-null. The projection is total
     *                    over every {@link OfferStatus} value — the terminal {@code REJECTED} /
     *                    {@code WITHDRAWN} map to {@link OfferVisibilityStatus#CLOSED}
     * @return the derived visibility projection
     * @throws IllegalArgumentException if {@code offerStatus} is {@code null}
     */
    public OfferVisibilityStatus visibilityOf(OfferStatus offerStatus) {
        if (offerStatus == null) {
            throw new IllegalArgumentException("offerStatus must not be null");
        }
        return switch (offerStatus) {
            case DRAFT -> OfferVisibilityStatus.DRAFT;
            case SENT, CHANGES_REQUESTED, COUNTERED -> OfferVisibilityStatus.ON_APPROVAL;
            case APPROVED -> OfferVisibilityStatus.APPROVED;
            case REJECTED, WITHDRAWN -> OfferVisibilityStatus.CLOSED;
        };
    }

    /**
     * Asserts a CLIENT may see the offer at the given lifecycle status: it is client-visible unless
     * its derived visibility is {@link OfferVisibilityStatus#DRAFT}. Returns normally when the offer
     * is client-visible ({@code ON_APPROVAL}, {@code APPROVED}, or the terminal {@code CLOSED} — the
     * owning client may read its own rejected/withdrawn offer); throws the
     * {@code Access_Denied_Outcome} otherwise.
     *
     * <p>The denial is a {@code 404 error.entity.not.found} carrying the offer id, so a
     * {@code DRAFT}-visibility offer is indistinguishable from a missing one and is never revealed
     * to the client (Requirements 5.9, 17.5), matching the {@code ProjectScopedService} convention.
     *
     * @param offerStatus the offer's lifecycle status
     * @param offerId     the offer id, echoed into the not-found message parameters
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the offer's visibility is
     *                             {@link OfferVisibilityStatus#DRAFT}
     */
    public void assertClientVisible(OfferStatus offerStatus, Object offerId) {
        if (visibilityOf(offerStatus) == OfferVisibilityStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, offerId);
        }
    }
}
