package com.foremen.dao.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
 * FOR-05-03 (Requirement 2): one line of an {@link EstimateEntity} for a single catalog
 * work. The line references a {@link WorkItemEntity} but freezes its own denormalized
 * {@code unitPrice} snapshot so its value is stable and does not shift when the catalog
 * changes.
 *
 * <p>Mirrors the {@code estimate_lines} table (changeset {@code 077-create-estimate-lines.xml},
 * design §4.2 / §4.4):
 * <ul>
 *   <li>{@code estimate} — owner FK (ON DELETE CASCADE at the DB level).</li>
 *   <li>{@code workItem} — catalog work reference (ON DELETE RESTRICT).</li>
 *   <li>{@code workPrice} — PROVENANCE FK only, nullable (ON DELETE SET NULL); records
 *       which catalog price the snapshot came from and never drives the numeric value
 *       (R2.4, R2.5).</li>
 *   <li>{@code unit} — the work's measurement unit (ON DELETE RESTRICT).</li>
 *   <li>{@code unitPrice} — the frozen snapshot, source of truth for the line's value
 *       (R2.3).</li>
 *   <li>{@code quantity} — DERIVED = sum of room quantities (R2.6); never hand-entered.</li>
 *   <li>{@code valueNet} — DERIVED = {@code unitPrice * quantity} (R2.7); never
 *       hand-entered.</li>
 * </ul>
 * The derived columns default to {@code 0} so a freshly-inserted line is well-formed
 * before the first recompute (a line with no room quantities reads {@code 0} — R3.4).
 */
@Entity
@Table(name = "estimate_lines")
@Getter
@Setter
@NoArgsConstructor
public class EstimateLineEntity extends BaseEntity {

    /** Owner FK to the estimate this line belongs to (R2.1). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_id", nullable = false)
    private EstimateEntity estimate;

    /** Catalog work this line refers to (R2.1). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItemEntity workItem;

    /**
     * PROVENANCE FK only (R2.4): lineage to the catalog {@link WorkPriceEntity} the
     * {@link #unitPrice} snapshot was copied from. Nullable — a catalog delete nulls this
     * pointer (ON DELETE SET NULL) without touching {@link #unitPrice} or the line (R2.5).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_price_id")
    private WorkPriceEntity workPrice;

    /** The work's measurement unit (R2.2). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false)
    private MeasurementUnitEntity unit;

    /** Ordinal of the line within the estimate (R2.2). */
    @Column(name = "line_no")
    private Integer lineNo;

    /** Optional free-text comment (R2.2). */
    @Column(name = "comment", length = 1024)
    private String comment;

    /** Denormalized snapshot unit price — the frozen source of truth for the value (R2.3). */
    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    /** DERIVED = sum of {@code EstimateLineRoomQty.quantity}; never hand-entered (R2.6). */
    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    /** DERIVED = {@code unitPrice * quantity}; never hand-entered (R2.7). */
    @Column(name = "value_net", nullable = false)
    private BigDecimal valueNet = BigDecimal.ZERO;

    /**
     * Read-side view of the line's per-room quantities, used by {@code EstimateRecomputeService}
     * to compute {@link #quantity} = {@code Σ roomQty.quantity} per design §6.1. Not cascaded from
     * this side — room quantities are owned/persisted by {@code EstimateLineRoomQtyService};
     * deletion cascade is enforced at the DB level (the {@code line_id} FK is
     * {@code ON DELETE CASCADE}).
     */
    @OneToMany(mappedBy = "line", fetch = FetchType.LAZY)
    private List<EstimateLineRoomQtyEntity> roomQtys = new ArrayList<>();
}
