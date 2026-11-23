package com.foremen.service.offer;

/**
 * FOR-05-07b Round 2 (D5): published by {@code OfferService.reprepare} when a non-DRAFT offer was
 * rebuilt from the estimate. {@link OfferNotificationEmitter} consumes it after commit and notifies
 * the CLIENT members of the project. Carries only ids and plain values, never a loaded entity.
 *
 * @param offerId   the updated offer
 * @param projectId the owning project
 * @param offerName the offer name (kept by the update)
 * @param revision  the new offer version
 */
public record OfferRepreparedEvent(Long offerId, Long projectId, String offerName, int revision) {
}
