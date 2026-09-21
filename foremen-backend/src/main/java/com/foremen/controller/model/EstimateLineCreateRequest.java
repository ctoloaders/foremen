package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Inbound create request for {@code EstimateLine} (FOR-05-03, Requirement 2). {@code workPriceId}
 * is the optional provenance-only reference (nullable, never drives value — R2.4). The derived
 * {@code quantity}/{@code valueNet} are deliberately absent here: they are never settable inputs
 * (R2.6, R2.7, R8.4) — computed exclusively by {@code EstimateRecomputeService}.
 */
public record EstimateLineCreateRequest(
        @NotNull Long estimateId,
        @NotNull Long workItemId,
        Long workPriceId,
        @NotNull Long unitId,
        Integer lineNo,
        String comment,
        @NotNull BigDecimal unitPrice
) {}
