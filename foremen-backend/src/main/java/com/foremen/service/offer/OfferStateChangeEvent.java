package com.foremen.service.offer;

import com.foremen.dao.model.OfferStatus;

/**
 * Immutable application event carrying an {@code Offer} lifecycle state change (FOR-05-07,
 * Requirement 7.1; design §OfferService {@code approve()}).
 *
 * <p>Published by {@code OfferService} <em>after</em> a lifecycle transition has been persisted (most
 * significantly on {@code approve()}, when the offer reaches {@link OfferStatus#APPROVED} and its
 * {@code Agreed_Offer_Version} is recorded). Downstream consumers — FOR-05-14 (price freeze into an
 * {@code OfferPriceSnapshot} / {@code OFFER_BASE} seed) and FOR-05-09 (contract generation) — read
 * this event to react to an approved offer without {@code OfferService} depending on those specs.
 *
 * <p>Following the repo's {@code InvitationEmailEvent} convention, this is a plain immutable record
 * published through {@code ApplicationEventPublisher}; consumers subscribe with {@code @EventListener}
 * (or {@code @TransactionalEventListener} for after-commit reaction) so a consumer failure never
 * rolls back the offer transition.
 *
 * @param offerId          the offer whose status changed
 * @param projectId        the owning project id
 * @param estimateId       the referenced estimate id
 * @param previousStatus   the offer status before the transition
 * @param newStatus        the offer status after the transition
 * @param approvedRevision the recorded {@code Agreed_Offer_Version} when {@code newStatus} is
 *                         {@link OfferStatus#APPROVED}; {@code null} for non-approval transitions
 */
public record OfferStateChangeEvent(
        Long offerId,
        Long projectId,
        Long estimateId,
        OfferStatus previousStatus,
        OfferStatus newStatus,
        Integer approvedRevision) {
}
