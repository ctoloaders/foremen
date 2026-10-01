package com.foremen.service.offer;

import java.math.BigDecimal;

/**
 * One commercial package's client-facing <b>offer price</b> in the {@link ClientOfferReadModel}
 * (FOR-05-07, Requirements 15.1, 15.2, 19.3).
 *
 * <p>Confidentiality-safe projection of an {@code OfferPackage}: it carries only the package
 * identity, its localized label, its zł/m² headline, and the offer-facing net/gross totals the
 * client may see. It holds <b>no</b> cost, margin, worker rate, or estimate-internal unit-price
 * field (Property 21).
 *
 * @param packageCode the package code (identity)
 * @param packageName the package display label (localized at the read layer)
 * @param selected    whether this package is the offer's currently selected package
 * @param zlM2        the package headline zł/m², or {@code null} when not yet computed
 * @param offerNet    the client-facing net offer total for this package, or {@code null}
 * @param offerGross  the client-facing gross offer total for this package, or {@code null}
 */
public record PackagePriceView(
        String packageCode,
        String packageName,
        boolean selected,
        BigDecimal zlM2,
        BigDecimal offerNet,
        BigDecimal offerGross) {
}
