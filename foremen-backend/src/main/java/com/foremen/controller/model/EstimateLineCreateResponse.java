package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Outbound create response for {@code EstimateLine} (FOR-05-03, Requirement 2), including the
 * derived {@code quantity}/{@code valueNet} (R2.6, R2.7 — zero until room quantities are added,
 * R3.4).
 */
public record EstimateLineCreateResponse(
        Long id,
        Long estimateId,
        Long workItemId,
        Long workPriceId,
        Long unitId,
        Integer lineNo,
        String comment,
        BigDecimal unitPrice,
        BigDecimal quantity,
        BigDecimal valueNet
) {}
