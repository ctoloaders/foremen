package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The estimate's <b>frozen</b> copied-price material entry for a cell — one row per
 * {@code (estimate line, room, branch, material type)} (FOR-05-05 design §B1, R13/R6/R4). Maps the
 * {@code estimate_line_room_materials} table (changeset
 * {@code 102-create-estimate-line-room-materials.xml}).
 *
 * <p>Owned by {@link EstimateLineRoomQtyEntity} via the {@code room_qty_id} FK
 * ({@code ON DELETE CASCADE}); the material rows cascade-delete with their room-qty and, transitively,
 * with the owning line (R19.3). The row is keyed uniquely per assignment by branch and type — DB
 * {@code UNIQUE (room_qty_id, branch, construction_type_id, finishing_type_id)} (R13.4).
 *
 * <p>{@code branch} ({@link ConsumptionBranch}) selects which of the two nullable type FKs is set:
 * exactly one of {@link #constructionType} / {@link #finishingType} is non-null, matching the branch
 * (both {@code ON DELETE RESTRICT}). {@code normQty} is the copied consumption norm per one work-unit
 * (R4.2); {@code rangeMin}/{@code rangeMax} the copied {@code Type_Price_Range} (per one work-unit
 * money-band edge, R13.2). The {@code source*Material} FKs are provenance only — the catalog material
 * that contributed the range ({@code ON DELETE SET NULL}, R13.2) — and never drive value.
 *
 * <p><b>Placeholder vs concrete (R6.6, R6.4).</b> A row with no {@code concrete*Material} is a
 * Placeholder (contributes its {@code rangeMin..rangeMax}). Setting a concrete product copies its
 * {@code retailNet} into {@link #concreteNet}, collapsing the line to a point ({@code min == max});
 * the concrete FKs are {@code ON DELETE SET NULL}.
 */
@Entity
@Table(name = "estimate_line_room_materials")
@Getter
@Setter
@NoArgsConstructor
public class EstimateLineRoomMaterialEntity extends BaseEntity {

    /** Owning room-qty (R19.3); deleting it (or its line) cascade-removes this material row. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_qty_id", nullable = false)
    private EstimateLineRoomQtyEntity roomQty;

    /** Which branch this material line belongs to — {@code construction} | {@code finishing}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConsumptionBranch branch;

    /** Construction material type reference (set iff {@code branch == construction}); ON DELETE RESTRICT. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "construction_type_id")
    private ConstructionMaterialTypeEntity constructionType;

    /** Finishing material type reference (set iff {@code branch == finishing}); ON DELETE RESTRICT. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finishing_type_id")
    private MaterialTypeEntity finishingType;

    /** Copied consumption norm per one work-unit (R4.2). */
    @Column(name = "norm_qty", precision = 12, scale = 4)
    private BigDecimal normQty;

    /** Copied {@code Type_Price_Range} min (per one work-unit money-band edge, R13.2). */
    @Column(name = "range_min", precision = 12, scale = 2)
    private BigDecimal rangeMin;

    /** Copied {@code Type_Price_Range} max. */
    @Column(name = "range_max", precision = 12, scale = 2)
    private BigDecimal rangeMax;

    /** Provenance: the catalog construction material that contributed the range; ON DELETE SET NULL (R13.2). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_construction_material_id")
    private ConstructionMaterialEntity sourceConstructionMaterial;

    /** Provenance: the catalog finishing material that contributed the range; ON DELETE SET NULL (R13.2). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_finishing_material_id")
    private FinishingMaterialEntity sourceFinishingMaterial;

    /** Chosen concrete construction product; ON DELETE SET NULL. Null ⇒ Placeholder (R6.6). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concrete_construction_material_id")
    private ConstructionMaterialEntity concreteConstructionMaterial;

    /** Chosen concrete finishing product; ON DELETE SET NULL. Null ⇒ Placeholder (R6.6). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concrete_finishing_material_id")
    private FinishingMaterialEntity concreteFinishingMaterial;

    /** Copied chosen product {@code retailNet}; collapses the line to a point (R6.4). */
    @Column(name = "concrete_net", precision = 12, scale = 2)
    private BigDecimal concreteNet;

    /**
     * The explicit physical quantity when this line is manually overridden (FOR-05-05 amendment #1):
     * a per-material-line override that fixes the line's physical quantity independent of
     * {@code norm × Volume}. Non-null and {@code >= 0} only while {@link #qtyOverridden} is
     * {@code true}; otherwise {@code null}.
     */
    @Column(name = "manual_qty", precision = 12, scale = 4)
    private BigDecimal manualQty;

    /**
     * Whether {@link #manualQty} overrides this line's derived physical quantity (FOR-05-05 amendment
     * #1). When {@code true} the resolved physical quantity is {@link #manualQty} (which wins over the
     * consumption basis); when {@code false} the quantity derives from {@code norm × Volume} (PER_UNIT)
     * or {@code norm} (PER_ROOM). Defaults to {@code false}.
     */
    @Column(name = "qty_overridden", nullable = false)
    private boolean qtyOverridden = false;

    /**
     * The copied consumption BASIS (FOR-05-05 amendment #4) so the frozen estimate is self-contained:
     * {@code PER_UNIT} ⇒ physical quantity {@code norm × Volume}; {@code PER_ROOM} ⇒ {@code norm}
     * (once per assigned room, independent of Volume). Copied from the work's
     * {@code WorkMaterialConsumption} at assign/add time; defaults to {@code PER_UNIT}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_basis", nullable = false, length = 32)
    private ConsumptionBasis consumptionBasis = ConsumptionBasis.PER_UNIT;

    /**
     * Whether this material line was placed by an applied offer package (FOR-05-05 Amendment A1,
     * point 2). A package-flagged line carries a fixed per-room package allocation quantity and the
     * position's package price band; it is otherwise an ordinary material line (point 4). Re-applying
     * a package replaces all package-flagged lines with the new package's lines (point 5). Defaults
     * to {@code false}.
     */
    @Column(name = "applied_from_package", nullable = false)
    private boolean appliedFromPackage = false;
}
