package com.foremen.dao.model;

import java.math.BigDecimal;

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
 * The per-package price row for an {@link AssortmentPositionEntity} (FOR-05-04-UI assortment
 * rework): a position's min/avg/max price under one {@link OfferPackageEntity}. At most one row
 * per (position, package) (DB {@code UNIQUE (assortment_position_id, offer_package_id)}).
 *
 * <p>Only {@code avgPrice} feeds the package zł/m² formula; a {@code null} {@code avgPrice}
 * contributes 0 for that package. {@code minPrice}/{@code maxPrice} are exposed for the review
 * band. Not project-scoped (global catalog).
 *
 * <ul>
 *   <li>{@code position} — owner FK to {@link AssortmentPositionEntity} (ON DELETE CASCADE):
 *       deleting the position removes its price rows.</li>
 *   <li>{@code offerPackage} — reference FK to {@link OfferPackageEntity} (ON DELETE RESTRICT):
 *       a package referenced by a price row cannot be silently removed.</li>
 * </ul>
 */
@Entity
@Table(name = "assortment_position_prices")
@Getter
@Setter
@NoArgsConstructor
public class AssortmentPositionPriceEntity extends BaseEntity {

    /** Owner FK: deleting the position removes its price rows (ON DELETE CASCADE). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assortment_position_id", nullable = false)
    private AssortmentPositionEntity position;

    /** Per-package price row (ON DELETE RESTRICT). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_package_id", nullable = false)
    private OfferPackageEntity offerPackage;

    @Column(name = "min_price")
    private BigDecimal minPrice;

    @Column(name = "avg_price")
    private BigDecimal avgPrice;

    @Column(name = "max_price")
    private BigDecimal maxPrice;

    /**
     * Optional per-band quantity override (FOR-05-04-UI). When non-null, this band's zł/m²
     * contribution uses this quantity instead of the owning group's {@code referenceQty};
     * {@code null} falls back to the group value.
     */
    @Column(name = "min_qty")
    private BigDecimal minQty;

    @Column(name = "avg_qty")
    private BigDecimal avgQty;

    @Column(name = "max_qty")
    private BigDecimal maxQty;
}
