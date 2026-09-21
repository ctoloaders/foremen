package com.foremen.controller.model;

import com.foremen.dao.model.DiscountKind;

import java.math.BigDecimal;

/**
 * List/read DTO for an {@code EstimateLinePackagePrice} — the denormalized per-package project
 * price copied from the FOR-04-12b catalog at add-time (FOR-05-03, Requirements 4, 5). Mirrors
 * {@link com.foremen.service.model.EstimateLinePackagePriceServiceModel}: flat FK ids paired with
 * the resolved {@code offerPackageName}, the provenance-only {@code workPackagePriceId}, and the
 * snapshot/derived pricing fields.
 */
public record EstimateLinePackagePriceDtoModel(
        Long id,
        Long lineId,
        Long offerPackageId,
        String offerPackageName,
        Long workPackagePriceId,
        BigDecimal originalUnitPrice,
        DiscountKind discountKind,
        BigDecimal discountValue,
        BigDecimal unitPrice,
        boolean unpriced
) {}
