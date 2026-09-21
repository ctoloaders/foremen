package com.foremen.controller.model;

import com.foremen.dao.model.DiscountKind;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only DTO for an {@code EstimateLinePackagePriceHistory} row — the append-only change-capture
 * snapshot of a per-package project price (FOR-05-03, Requirement 6.3). Mirrors
 * {@link com.foremen.service.model.EstimateLinePackagePriceHistoryServiceModel}; there is
 * intentionally no create/update counterpart since history is append-only and never
 * client-written.
 */
public record EstimateLinePackagePriceHistoryDto(
        Long id,
        Long packagePriceId,
        BigDecimal originalUnitPrice,
        DiscountKind discountKind,
        BigDecimal discountValue,
        BigDecimal unitPrice,
        String changedBy,
        LocalDateTime changedAt
) {}
