package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * EstimateLinePackagePrice — the denormalized per-package project price snapshot copied from the
 * FOR-04-12b catalog at add-time (FOR-05-03, Requirements 4, 5).
 *
 * <p>One row per {@code (line, offer package existing at add-time)} (UNIQUE
 * {@code (line_id, offer_package_id)}, R4.2). The {@code offerPackage} is a reference; the
 * {@code workPackagePrice} is a <b>provenance FK only</b> ({@code ON DELETE SET NULL}) that records
 * the catalog lineage and never drives the stored value (R4.4, R4.5, design §4.4).
 *
 * <p>{@code originalUnitPrice} is the value copied from the catalog at add-time (NULL when the
 * package is unpriced, in which case {@code unpriced=true} and both prices are NULL — R4.7). The
 * effective {@code unitPrice} is <b>derived</b> from {@code (originalUnitPrice, discountKind,
 * discountValue)} via {@code applyDiscount} (R5.2, R5.3); it is never hand-entered.
 *
 * <p>Mirrors the {@code estimate_line_package_prices} table (changeset 079).
 */
@Entity
@Table(name = "estimate_line_package_prices",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_elpp_line_package",
                columnNames = {"line_id", "offer_package_id"}))
@Getter
@Setter
@NoArgsConstructor
public class EstimateLinePackagePriceEntity extends BaseEntity {

    /** Owner FK: the estimate line this per-package price belongs to (R4.1). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id", nullable = false)
    private EstimateLineEntity line;

    /** Reference FK: the offer package this price is for (R4.2). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_package_id", nullable = false)
    private OfferPackageEntity offerPackage;

    /**
     * Provenance FK only ({@code ON DELETE SET NULL}): the catalog per-package price this snapshot
     * was copied from. Records lineage; never drives the stored value. May become NULL after the
     * catalog row is deleted, leaving the snapshot intact (R4.4, R4.5, design §4.4).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_package_price_id")
    private WorkPackagePriceEntity workPackagePrice;

    /** The value copied from the catalog at add-time; NULL when unpriced (R4.3, R4.7). */
    @Column(name = "original_unit_price")
    private BigDecimal originalUnitPrice;

    /** Discount placeholder kind (R5.1); NULL for the neutral (no-discount) state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_kind", length = 20)
    private DiscountKind discountKind;

    /** Discount placeholder value (R5); NULL for the neutral (no-discount) state. */
    @Column(name = "discount_value")
    private BigDecimal discountValue;

    /**
     * Derived effective unit price = applyDiscount(originalUnitPrice, discountKind, discountValue)
     * (R5.2, R5.3); NULL when unpriced. Never hand-entered.
     */
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** TRUE when the resolver returned empty at add-time and prices are NULL (R4.7). */
    @Column(name = "unpriced", nullable = false)
    private boolean unpriced = false;
}
