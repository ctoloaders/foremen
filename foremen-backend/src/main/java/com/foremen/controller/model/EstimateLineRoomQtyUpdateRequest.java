package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Update payload for a per-room estimate-line quantity; same shape as
 * {@link EstimateLineRoomQtyCreateRequest} (FOR-05-03, Requirement 3).
 */
public record EstimateLineRoomQtyUpdateRequest(
        @NotNull Long lineId,
        @NotNull Long roomId,
        @NotNull BigDecimal quantity
) {}
