package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * List/read DTO for an {@code EstimateLine} (FOR-05-03, Requirement 2). Mirrors
 * {@link com.foremen.service.model.EstimateLineServiceModel}: flat FK ids paired with their resolved
 * reference names ({@code workItemName}, {@code unitName}), the frozen {@code unitPrice} snapshot
 * (source of truth, R2.3), and the derived {@code quantity}/{@code valueNet} (read-only — never
 * accepted from a client payload, R2.6, R2.7, R8.4). {@code workPriceId} is the provenance-only
 * reference (nullable, never drives value — R2.4).
 */
public record EstimateLineDtoModel(
        Long id,
        Long estimateId,
        Long workItemId,
        String workItemName,
        Long workPriceId,
        Long unitId,
        String unitName,
        Integer lineNo,
        String comment,
        BigDecimal unitPrice,
        BigDecimal quantity,
        BigDecimal valueNet
) {}
