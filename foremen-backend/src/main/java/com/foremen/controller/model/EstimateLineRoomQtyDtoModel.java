package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * List/read DTO for an {@code EstimateLineRoomQty} — the per-room quantity split of an estimate
 * line (FOR-05-03, Requirement 3). Mirrors {@link com.foremen.service.model.EstimateLineRoomQtyServiceModel}:
 * flat FK ids paired with the resolved {@code roomLabel}.
 */
public record EstimateLineRoomQtyDtoModel(
        Long id,
        Long lineId,
        Long roomId,
        String roomLabel,
        BigDecimal quantity
) {}
