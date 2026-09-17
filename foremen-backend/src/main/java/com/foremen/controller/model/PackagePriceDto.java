package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * A single entry in {@link WorkPriceDtoModel#prices()} — the effective price for one offer package.
 *
 * @param offerPackageId the numeric id of the offer package this price belongs to (stable across
 *                       {@code code} renames; used by the frontend to build the {@code prices.{id}.netPrice}
 *                       pivot filter/sort key)
 * @param currencyCode   ISO code of the currency of the effective price
 * @param netPrice       the effective net price for this package
 */
public record PackagePriceDto(Long offerPackageId, String currencyCode, BigDecimal netPrice) {}
