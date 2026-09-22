package com.foremen.dao.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One curated catalog row within an {@link AssortmentGroupEntity} group (e.g. "Miska WC" under
 * "Sanitariat/łazienka"), carrying min/avg/max price PER {@link OfferPackageEntity} and a
 * quantity assumed against the fixed 50 m² reference apartment ({@link #qtyRef50}), mirroring the
 * {@code Zestawienie} workbook (Requirements 6.1, 6.2, 6.7). Not project-scoped (global catalog).
 *
 * <ul>
 *   <li>{@code group} — owner FK to {@link AssortmentGroupEntity} (ON DELETE CASCADE at the DB
 *       level): deleting the group removes its line items.</li>
 *   <li>{@code offerPackage} — reference FK to {@link OfferPackageEntity} (ON DELETE RESTRICT): a
 *       package referenced by a line item cannot be silently removed (R6.2).</li>
 *   <li>{@code typicalProduct} — PROVENANCE FK only, nullable (ON DELETE SET NULL): an assistive
 *       "typical product" link that must never drive the stored {@link #minPrice}/{@link
 *       #avgPrice}/{@link #maxPrice} (R6.6, R6.7). A catalog delete nulls this pointer without
 *       touching the line item.</li>
 * </ul>
 */
@Entity
@Table(name = "assortment_line_items")
@Getter
@Setter
@NoArgsConstructor
public class AssortmentLineItemEntity extends BaseEntity {

    /** Owner FK: deleting the group removes its line items (ON DELETE CASCADE, R6.1). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assortment_group_id", nullable = false)
    private AssortmentGroupEntity group;

    /** Per-package price row (ON DELETE RESTRICT, R6.2). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_package_id", nullable = false)
    private OfferPackageEntity offerPackage;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(name = "min_price")
    private BigDecimal minPrice;

    @Column(name = "avg_price")
    private BigDecimal avgPrice;

    @Column(name = "max_price")
    private BigDecimal maxPrice;

    /** Quantity assumed against the 50 m² reference apartment (R6.2, R6.3). */
    @Column(name = "qty_ref50", nullable = false)
    private BigDecimal qtyRef50;

    /**
     * PROVENANCE FK only (R6.6, R6.7): assistive "typical product" link. Nullable — a catalog
     * delete nulls this pointer (ON DELETE SET NULL) without touching the line item. Must never
     * drive {@link #minPrice}/{@link #avgPrice}/{@link #maxPrice}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "typical_material_id")
    private MaterialEntity typicalProduct;
}
