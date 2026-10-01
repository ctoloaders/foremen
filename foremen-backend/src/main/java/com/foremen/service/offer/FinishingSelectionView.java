package com.foremen.service.offer;

import java.math.BigDecimal;

/**
 * One finishing-material selection surface entry in the client-facing {@link ClientOfferReadModel}
 * (FOR-05-07, Requirements 11.x, 15.1, 15.2, 19.3).
 *
 * <p>Confidentiality-safe projection for the client finishing-Placeholder selection UI: it shows the
 * finishing line, whether it is still a Placeholder (no concrete product chosen yet), and either the
 * offer-facing {@code Type_Price_Range} (min/max <em>offer</em> price for the type) or the chosen
 * product's offer price. It holds <b>no</b> self-cost, cost, margin, worker rate, or
 * estimate-internal unit-price field — every price here is an offer-facing client price (Property
 * 21).
 *
 * @param materialLineId   the finishing estimate line id (reference only)
 * @param materialLineName the finishing line display label (localized at the read layer)
 * @param placeholder      whether the line is still a Placeholder (no concrete product chosen)
 * @param chosenProductId  the chosen concrete product id, or {@code null} when still a Placeholder
 * @param chosenProductName the chosen product display label, or {@code null}
 * @param priceRangeMin    the offer-facing minimum type price (Type_Price_Range low), or {@code null}
 * @param priceRangeMax    the offer-facing maximum type price (Type_Price_Range high), or {@code null}
 * @param chosenOfferPrice the chosen product's offer price, or {@code null} when still a Placeholder
 */
public record FinishingSelectionView(
        Long materialLineId,
        String materialLineName,
        boolean placeholder,
        Long chosenProductId,
        String chosenProductName,
        BigDecimal priceRangeMin,
        BigDecimal priceRangeMax,
        BigDecimal chosenOfferPrice) {
}
