package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Extended DTO for an {@code EstimateLine} (FOR-05-03, Requirement 2), mapped from
 * {@link com.foremen.service.model.EstimateLineServiceExtendedModel}. Exposes the flat FK ids, the
 * frozen {@code unitPrice} snapshot, and the derived {@code quantity}/{@code valueNet} (read-only,
 * R2.6, R2.7, R8.4).
 */
public record EstimateLineDtoExtendedModel(
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
