package com.foremen.service.offer;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * <p>Round 4: an optional staff-only {@link LabourPriceComparison} (original vs effective labour
 * net). It is populated ONLY for ADMIN/MANAGER/ESTIMATOR/FINANCIER and is {@code null} (omitted from
 * JSON) otherwise — see {@link ClientOfferReadModelAssembler#canSeeLabourComparison(String)}.
 *
 * @param offer             the shared offer projection (identical to what the client is served)
 * @param control           the manager-only offer-workflow control state
 * @param labourComparison  staff-only original-vs-effective labour totals; {@code null} when the
 *                          caller role is not allowed to see the original labour price
 */
public record ExecutorOfferReadModel(
        ClientOfferReadModel offer,
        ManagerControlState control,
        @JsonInclude(JsonInclude.Include.NON_NULL) LabourPriceComparison labourComparison) {

    /** Executor model without the staff-only comparison block. */
    public ExecutorOfferReadModel(ClientOfferReadModel offer, ManagerControlState control) {
        this(offer, control, null);
    }

    /**
     * Labour totals of the offer's estimate lines BEFORE offer discounts: the ORIGINAL
     * ({@code Σ round2(unitPrice × qty)}) vs the EFFECTIVE ({@code Σ round2(coalesce(override,
     * unitPrice) × qty)}).
     */
    public record LabourPriceComparison(BigDecimal originalLabourNet, BigDecimal effectiveLabourNet) {
    }
}
