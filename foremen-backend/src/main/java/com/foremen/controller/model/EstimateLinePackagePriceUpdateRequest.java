package com.foremen.controller.model;

import com.foremen.dao.model.DiscountKind;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Update payload for a per-package estimate-line price (FOR-05-03, Requirements 4, 5).
 *
 * <p>Same client-owned shape as {@link EstimateLinePackagePriceCreateRequest}: only the discount
 * placeholder fields ({@code discountKind}, {@code discountValue}) plus the FKs are exposed. The
 * snapshot/derived fields are never client-supplied on update either — the service re-derives the
 * effective {@code unitPrice} from the row's existing {@code originalUnitPrice} and the (possibly
 * just-changed) discount fields (R5.2, R5.3).
 */
public record EstimateLinePackagePriceUpdateRequest(
        @NotNull Long lineId,
        @NotNull Long offerPackageId,
        DiscountKind discountKind,
        BigDecimal discountValue
) {}
