package com.foremen.service.offer;

import java.math.BigDecimal;

/**
 * One work-category's client-facing <b>offer price</b> subtotal in the {@link ClientOfferReadModel}
 * (FOR-05-07, Requirements 15.1, 15.2, 19.3).
 *
 * <p>Confidentiality-safe projection: it carries ONLY the offer-facing net/gross subtotal the client
 * may see, keyed by the work-category id. It holds <b>no</b> cost, margin, worker rate, or
 * estimate-internal unit-price field (Property 21).
 *
 * @param categoryId   the work-category id (reference only)
 * @param categoryName the category display label (localized at the read layer)
 * @param offerNet     the client-facing net offer subtotal for the category, or {@code null}
 * @param offerGross   the client-facing gross offer subtotal for the category, or {@code null}
 */
public record PerCategoryOfferPrice(
        Long categoryId,
        String categoryName,
        BigDecimal offerNet,
        BigDecimal offerGross) {
}
