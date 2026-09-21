package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Inbound update request for {@code EstimateLine} (FOR-05-03, Requirement 2). {@code estimateId} is
 * not included since a line's owning estimate is fixed at create time. The derived
 * {@code quantity}/{@code valueNet} are deliberately absent — never settable inputs (R2.6, R2.7,
 * R8.4).
 */
public record EstimateLineUpdateRequest(
        @NotNull Long workItemId,
        Long workPriceId,
        @NotNull Long unitId,
        Integer lineNo,
        String comment,
        @NotNull BigDecimal unitPrice
) {}
