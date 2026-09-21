package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Update response for a per-room estimate-line quantity, mirroring the persisted extended service
 * model (FOR-05-03, Requirement 3).
 */
public record EstimateLineRoomQtyUpdateResponse(
        Long id,
        Long lineId,
        Long roomId,
        BigDecimal quantity
) {}
