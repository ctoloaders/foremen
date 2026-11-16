package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * FOR-11-06 (R10.1): input of {@code POST /api/purchase-positions/buyer-added}. The sourcing path, the
 * flags and the status are system-owned and deliberately absent. {@code unitId} and the VAT rates default
 * as in the generic create. {@code externalOrderId} (a DRAFT order of the same project) and
 * {@code purchaseRequestId} are optional attach targets.
 */
public record BuyerAddedPositionRequest(
        @NotNull Long projectId,
        @NotNull Long plannedMaterialId,
        @NotNull BigDecimal quantity,
        Long unitId,
        @NotNull BigDecimal plannedPurchasePrice,
        BigDecimal salePrice,
        Long purchaseVatRateId,
        Long saleVatRateId,
        Long externalOrderId,
        Long purchaseRequestId,
        @Size(max = 2000) String note) {
}
