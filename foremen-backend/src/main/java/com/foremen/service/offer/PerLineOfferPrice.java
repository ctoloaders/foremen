package com.foremen.service.offer;

import java.math.BigDecimal;

/**
 * One estimate line's client-facing <b>offer price</b> in the {@link ClientOfferReadModel}
 * (FOR-05-07, Requirements 15.1, 15.2, 19.3).
 *
 * <p>This is a confidentiality-safe projection: it carries ONLY the offer-facing net/gross price the
 * client is allowed to see, keyed by the estimate line id. It deliberately holds <b>no</b>
 * self-cost, cost, margin, worker rate, worker-type tier, or estimate-internal unit-price field —
 * the line is referenced by id only and the price shown is the final offer price after discounts
 * (Property 21).
 *
 * @param lineId    the estimate line id this offer price belongs to (reference only, no estimate
 *                  internals)
 * @param lineName  the line display label (localized at the read layer)
 * @param offerNet  the client-facing net offer price for the line, or {@code null} when unpriced
 * @param offerGross the client-facing gross offer price for the line (net + VAT), or {@code null}
 */
public record PerLineOfferPrice(
        Long lineId,
        String lineName,
        BigDecimal offerNet,
        BigDecimal offerGross) {
}
