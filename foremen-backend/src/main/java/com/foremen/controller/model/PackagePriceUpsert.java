package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * One per-package price to upsert on a {@code WorkPrice} aggregator. Carried in the
 * {@code packagePrices} collection of the create/update request DTOs and the extended DTO.
 *
 * @param offerPackageId the offer package this price is for
 * @param currencyId     the currency of the price
 * @param netPrice       the net price (must be positive)
 */
public record PackagePriceUpsert(
    @NotNull Long offerPackageId,
    @NotNull Long currencyId,
    @NotNull @Positive BigDecimal netPrice
) {}
