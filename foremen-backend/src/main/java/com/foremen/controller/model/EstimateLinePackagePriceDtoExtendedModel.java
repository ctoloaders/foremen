package com.foremen.controller.model;

import com.foremen.dao.model.DiscountKind;

import java.math.BigDecimal;

/**
 * Extended DTO for an {@code EstimateLinePackagePrice}, mapped from
 * {@link com.foremen.service.model.EstimateLinePackagePriceServiceExtendedModel} (FOR-05-03,
 * Requirements 4, 5).
 */
public record EstimateLinePackagePriceDtoExtendedModel(
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
