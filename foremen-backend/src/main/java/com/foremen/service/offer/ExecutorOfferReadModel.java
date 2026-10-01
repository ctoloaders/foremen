package com.foremen.service.offer;

/**
 * The MANAGER/ADMIN-facing read model of an {@code Offer} (FOR-05-07, Requirements 15.1, 16). It is
 * served to executors instead of the client model on offer reads.
 *
 * <p>It embeds the same offer projection the client sees ({@link #offer}) plus the manager-only
 * control state ({@link #control}). It does <b>not</b> fold in the kosztorys cost/margin/worker-rate
 * read models: per Requirement 16 those remain their own MANAGER/ADMIN-only ESTIMATE / margins read
 * models, keeping the offer read models a single confidentiality boundary. Because it embeds
 * {@link ClientOfferReadModel} verbatim, the offer-price projection stays consistent between the two
 * audiences; the executor simply gets the extra {@link ManagerControlState} block on top.
 *
 * @param offer   the shared offer projection (identical to what the client is served)
 * @param control the manager-only offer-workflow control state
 */
public record ExecutorOfferReadModel(
        ClientOfferReadModel offer,
        ManagerControlState control) {
}
