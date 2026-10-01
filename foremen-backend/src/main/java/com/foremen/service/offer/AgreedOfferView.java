package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

/**
 * The agreed (approved) offer version consumed downstream by FOR-05-14 (price freeze) and FOR-05-09
 * (contract) (FOR-05-07, Requirements 7.2, 7.4).
 *
 * <p>It captures the {@code Agreed_Offer_Version}: the approved revision's totals, the selected
 * package, and the applied discounts. It is stable — once the offer is {@code APPROVED} these values
 * do not change (R7.2). It carries only offer-level, client-visible facts and holds <b>no</b> cost,
 * margin, worker rate, or estimate-internal unit-price field.
 *
 * @param offerId             the offer id
 * @param estimateId          the referenced estimate id ONLY
 * @param approvedRevision    the recorded {@code Agreed_Offer_Version} (the revision at APPROVED)
 * @param selectedPackageCode the selected package code at approval, or {@code null}
 * @param totalNet            the agreed net total
 * @param totalVat            the agreed VAT total
 * @param totalGross          the agreed gross total
 * @param appliedDiscounts    the applied discounts at approval
 */
public record AgreedOfferView(
        Long offerId,
        Long estimateId,
        Integer approvedRevision,
        String selectedPackageCode,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        List<AppliedDiscountView> appliedDiscounts) {
}
