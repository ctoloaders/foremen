package com.foremen.controller.model;

import com.foremen.dao.model.DiscountKind;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Create payload for a per-package estimate-line price (FOR-05-03, Requirements 4, 5).
 *
 * <p>{@code lineId} and {@code offerPackageId} are the mandatory FKs. The client owns ONLY the
 * discount placeholder fields ({@code discountKind}, {@code discountValue}); the snapshot/derived
 * fields ({@code originalUnitPrice}, the effective {@code unitPrice}, {@code unpriced}) are never
 * client-supplied — they are set by {@code PackagePriceSnapshotService} at add-time and by
 * {@code EstimateLinePackagePriceService}'s discount derivation, and the service-layer mapper
 * (task 5.2) ignores them inbound.
 */
public record EstimateLinePackagePriceCreateRequest(
        @NotNull Long lineId,
        @NotNull Long offerPackageId,
        DiscountKind discountKind,
        BigDecimal discountValue
) {}
