package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.dao.model.VariantReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** FOR-11-00 (R21.3): body of {@code POST /api/purchase-positions/{id}/variants/replace}. */
public record VariantReplaceRequest(
        @NotNull Long materialId,
        BigDecimal salePrice,
        BigDecimal plannedPurchasePrice,
        @NotNull VariantReason reason,
        @Size(max = 500) String note) {
}
