package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Write-path service model for an {@code EstimateLineRoomQty} (FOR-05-03, Requirement 3).
 *
 * <p>Mutable ({@code @Data}) carrying the {@code line} and {@code room} FKs plus the per-room
 * {@code quantity}. The cross-project rule (a room's project must equal the line's estimate project,
 * R3.6) and the non-negative check (R3.2) are enforced at the service write path (task 8.3), not by
 * this model.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLineRoomQtyServiceExtendedModel {
    private Long id;
    private Long lineId;
    private Long roomId;

    /** Per-room quantity; must be non-negative — validated at the service layer (R3.2). */
    private BigDecimal quantity;
}
