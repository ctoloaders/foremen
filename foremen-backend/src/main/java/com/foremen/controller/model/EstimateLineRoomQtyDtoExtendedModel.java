package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Extended DTO for an {@code EstimateLineRoomQty}, mapped from
 * {@link com.foremen.service.model.EstimateLineRoomQtyServiceExtendedModel} (FOR-05-03,
 * Requirement 3).
 */
public record EstimateLineRoomQtyDtoExtendedModel(
        Long id,
        Long lineId,
        Long roomId,
        BigDecimal quantity
) {}
