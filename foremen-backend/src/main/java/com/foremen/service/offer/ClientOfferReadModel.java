package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.OfferVisibilityStatus;

/**
 * The confidential, client-facing read model of an {@code Offer} (FOR-05-07, Requirements 7.4, 15.1,
 * 15.2, 19.3). It is the <b>only</b> offer payload ever served to a CLIENT, assembled by
 * {@link ClientOfferReadModelAssembler}.
 *
 * <p><b>Confidentiality is a property of this type's whole graph (Property 21 / R15 / R19.3).</b>
 * Every field here and in every nested type it transitively references
 * ({@link PerLineOfferPrice}, {@link PerCategoryOfferPrice}, {@link PackagePriceView},
 * {@link AppliedDiscountView}, {@link FinishingSelectionView}, {@link NegotiationRoundView},
 * {@link OfferReadinessView}) is an <b>offer-level</b>, client-visible fact. The graph deliberately
 * contains <b>no</b> field whose name denotes a self-cost, cost, margin, worker rate, worker-type
 * tier, material {@code cost_net}, per-branch/per-tier margin, or estimate-internal unit price. The
 * referenced estimate appears ONLY as {@link #estimateId} — an opaque id, never an embedded estimate
 * DTO. This confidentiality is verified by the structural test in task 2.4 (Property 21).
 *
 * <p>The prices exposed are the final <b>offer</b> prices (estimate client-facing final price with
 * the offer's applied discounts on top), never estimate internals.
 *
 * @param offerId            the offer id
 * @param estimateId         the referenced estimate id ONLY (no embedded estimate DTO, R19.3)
 * @param projectId          the owning project id
 * @param status             the offer lifecycle status
 * @param visibilityStatus   the derived client-visibility projection
 * @param revision           the current offer revision
 * @param selectedPackageCode the currently selected package code, or {@code null}
 * @param totalNet           the client-facing net offer total
 * @param totalVat           the client-facing VAT offer total
 * @param totalGross         the client-facing gross offer total
 * @param perLineOfferPrice  the per-line client-facing offer prices
 * @param perCategoryOfferPrice the per-category client-facing offer subtotals
 * @param packagePrices      the per-package client-facing offer prices
 * @param appliedDiscounts   the applied discount lines
 * @param finishing          the finishing-material selection surface entries
 * @param negotiationThread  the ordered two-sided negotiation thread
 * @param readiness          the offer readiness projection, or {@code null} when not applicable
 */
public record ClientOfferReadModel(
        Long offerId,
        Long estimateId,
        Long projectId,
        OfferStatus status,
        OfferVisibilityStatus visibilityStatus,
        Integer revision,
        String selectedPackageCode,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        List<PerLineOfferPrice> perLineOfferPrice,
        List<PerCategoryOfferPrice> perCategoryOfferPrice,
        List<PackagePriceView> packagePrices,
        List<AppliedDiscountView> appliedDiscounts,
        List<FinishingSelectionView> finishing,
        List<NegotiationRoundView> negotiationThread,
        OfferReadinessView readiness) {
}
