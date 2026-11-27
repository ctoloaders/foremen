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
        @JsonInclude(JsonInclude.Include.NON_NULL) LabourPriceComparison labourComparison,
        boolean aiGenerated,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long aiJobId,
        @JsonInclude(JsonInclude.Include.NON_NULL) java.util.Map<String, Object> aiReport) {

    /** Executor model without the staff-only comparison block and without AI fields. */
    public ExecutorOfferReadModel(ClientOfferReadModel offer, ManagerControlState control) {
        this(offer, control, null);
    }

    /** Executor model without AI fields (FOR-05-04: {@code aiGenerated=false}). */
    public ExecutorOfferReadModel(ClientOfferReadModel offer, ManagerControlState control,
                                  LabourPriceComparison labourComparison) {
        this(offer, control, labourComparison, false, null, null);
    }

    /** FOR-05-04 (Req 8.5, 14.4, 17.1): copy of this model carrying the staff-only AI fields. */
    public ExecutorOfferReadModel withAi(boolean aiGenerated, Long aiJobId, java.util.Map<String, Object> aiReport) {
        return new ExecutorOfferReadModel(offer, control, labourComparison, aiGenerated, aiJobId, aiReport);
    }

    /**
     * Labour totals of the offer's estimate lines BEFORE offer discounts: the ORIGINAL
     * ({@code Σ round2(unitPrice × qty)}) vs the EFFECTIVE ({@code Σ round2(coalesce(override,
     * unitPrice) × qty)}).
     */
    public record LabourPriceComparison(BigDecimal originalLabourNet, BigDecimal effectiveLabourNet) {
    }
}
