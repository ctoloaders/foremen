package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A GLOBAL, per-group assortment position (FOR-05-04-UI assortment rework): a material-type-backed
 * row within an {@link AssortmentGroupEntity} group, shared across ALL offer packages. Its
 * per-package prices live in {@link AssortmentPositionPriceEntity}.
 *
 * <p>A position REQUIRES a {@link MaterialTypeEntity} (not free text), and a material type appears
 * at most once per group (DB {@code UNIQUE (assortment_group_id, material_type_id)}). Not
 * project-scoped (global catalog).
 *
 * <ul>
 *   <li>{@code group} — owner FK to {@link AssortmentGroupEntity} (ON DELETE CASCADE at the DB
 *       level): deleting the group removes its positions (and, via the price FK, their prices for
 *       every package).</li>
 *   <li>{@code materialType} — required FK to {@link MaterialTypeEntity} (ON DELETE RESTRICT): a
 *       material type referenced by a position cannot be silently removed.</li>
 *   <li>{@code sortOrder} — optional display order within the group.</li>
 * </ul>
 */
@Entity
@Table(name = "assortment_positions")
@Getter
@Setter
@NoArgsConstructor
public class AssortmentPositionEntity extends BaseEntity {

    /** Owner FK: deleting the group removes its positions (ON DELETE CASCADE). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assortment_group_id", nullable = false)
    private AssortmentGroupEntity group;

    /** Required material type (ON DELETE RESTRICT); a position REQUIRES a material type. */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "material_type_id", nullable = false)
    private MaterialTypeEntity materialType;

    /** Optional display order within the group. */
    @Column(name = "sort_order")
    private Integer sortOrder;
}
