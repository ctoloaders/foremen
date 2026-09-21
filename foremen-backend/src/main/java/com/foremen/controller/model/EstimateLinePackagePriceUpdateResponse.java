package com.foremen.controller.model;

import com.foremen.dao.model.DiscountKind;

import java.math.BigDecimal;

/**
 * Update response for a per-package estimate-line price, mirroring the persisted extended service
 * model (FOR-05-03, Requirements 4, 5).
 */
public record EstimateLinePackagePriceUpdateResponse(
        Long id,
        Long lineId,
        Long offerPackageId,
        Long workPackagePriceId,
        BigDecimal originalUnitPrice,
        DiscountKind discountKind,
        BigDecimal discountValue,
        BigDecimal unitPrice,
        boolean unpriced
) {}
