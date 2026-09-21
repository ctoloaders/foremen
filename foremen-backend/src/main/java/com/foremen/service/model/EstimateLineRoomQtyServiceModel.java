package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Read-path service model for an {@code EstimateLineRoomQty} — the per-room quantity split of an
 * estimate line (FOR-05-03, Requirement 3).
 *
 * <p>Flat FK ids paired with the resolved {@code roomLabel}. {@code quantity} is the per-room
 * quantity (non-negative, R3.2); it is an input value (not derived) that contributes to the owning
 * line's derived total quantity (R3.3).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLineRoomQtyServiceModel {
    private Long id;
    private Long lineId;
    private Long roomId;
    private String roomLabel;

    /** Per-room quantity; non-negative (R3.2). */
    private BigDecimal quantity;
}
