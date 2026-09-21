package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Create payload for a per-room estimate-line quantity (FOR-05-03, Requirement 3).
 *
 * <p>{@code lineId} and {@code roomId} are mandatory FKs. The cross-project rule (the room's
 * project must equal the line's estimate project, R3.6) and the non-negative check (R3.2) are
 * enforced at the service write path, not here.
 */
public record EstimateLineRoomQtyCreateRequest(
        @NotNull Long lineId,
        @NotNull Long roomId,
        @NotNull BigDecimal quantity
) {}
