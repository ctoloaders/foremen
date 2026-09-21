package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The per-room quantity split of an {@code EstimateLine} — one row per {@code (line, room)} pair,
 * from which the owning line's total {@code quantity} is derived as the sum across its rooms
 * (FOR-05-03 Requirement 3). Maps the {@code estimate_line_room_qty} table (changeset
 * {@code 078-create-estimate-line-room-qty.xml}).
 *
 * <p>The {@code line} FK owns the row (deleting the line cascades away its room quantities, per the
 * changeset). The {@code room} reuses the FOR-04-14 {@link RoomEntity}; a room referenced by a
 * quantity cannot be silently deleted (ON DELETE RESTRICT). The cross-project rule — a room's
 * project must equal the line's estimate project (R3.6) — is enforced at the service write path
 * (task 8.3), not by a DB constraint.
 *
 * <p>{@code quantity} is non-negative (R3.2, DB {@code CHECK (quantity >= 0)}) and stored as
 * {@code NUMERIC(12,4)}, mirroring the changeset column.
 */
@Entity
@Table(name = "estimate_line_room_qty")
@Getter
@Setter
@NoArgsConstructor
public class EstimateLineRoomQtyEntity extends BaseEntity {

    /** Owning estimate line (R3.1); deleting the line removes its room quantities. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    private EstimateLineEntity line;

    /** The room this quantity applies to (R3.1), reused from FOR-04-14. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private RoomEntity room;

    /** Per-room quantity; non-negative (R3.2). Contributes to the line's derived total quantity (R3.3). */
    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal quantity;
}
