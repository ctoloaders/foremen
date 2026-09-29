package com.foremen.dao.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    /**
     * FOR-05-05 (#7): when {@code true}, {@link #quantity} is a MANUAL Volume override the user set
     * for this {@code (line, room)} cell, and it MUST NOT be recomputed/overwritten by the formula on
     * recompute or reassign; when {@code false} (the default), {@code quantity} is the formula-resolved
     * Volume (existing behavior). Backed by {@code estimate_line_room_qty.volume_overridden} (changeset
     * {@code 106-add-estimate-line-room-qty-volume-overridden.xml}).
     */
    @Column(name = "volume_overridden", nullable = false)
    private boolean volumeOverridden = false;

    /**
     * The cell's frozen copied-price material lines (FOR-05-05 §B1) — one per
     * {@code (branch, material type)}. Owned by this room-qty: cascade + orphan removal make them
     * cascade-delete when this room-qty (and, transitively, its owning line) is removed (R19.3).
     */
    @OneToMany(mappedBy = "roomQty", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EstimateLineRoomMaterialEntity> materials = new ArrayList<>();
}
