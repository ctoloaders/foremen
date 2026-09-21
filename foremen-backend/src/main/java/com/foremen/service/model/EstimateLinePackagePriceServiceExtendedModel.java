package com.foremen.service.model;

import com.foremen.dao.model.DiscountKind;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Write-path service model for an {@code EstimateLinePackagePrice} (FOR-05-03, Requirements 4, 5).
 *
 * <p>Mutable ({@code @Data}). Clients own only the discount placeholder fields
 * ({@code discountKind}, {@code discountValue}) on a free edit; the effective {@code unitPrice} is
 * derived from {@code (originalUnitPrice, discountKind, discountValue)} via {@code applyDiscount}
 * and is never hand-entered (R5.2, R5.3).
 *
 * <p>{@code originalUnitPrice}, {@code unitPrice} and {@code unpriced} are set by the copy-at-add-time
 * snapshot ({@code PackagePriceSnapshotService}); they are exposed here for round-tripping but are
 * ignored on inbound create by the mapper (task 5.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLinePackagePriceServiceExtendedModel {
    private Long id;
    private Long lineId;
    private Long offerPackageId;

    /** Provenance FK only (nullable); never drives value (R4.4). */
    private Long workPackagePriceId;

    /** Copied at add-time by the snapshot service; ignored on inbound writes (R4.3). */
    private BigDecimal originalUnitPrice;

    /** Discount placeholder kind owned by the client on a free edit (R5.1). */
    private DiscountKind discountKind;

    /** Discount placeholder value owned by the client on a free edit (R5). */
    private BigDecimal discountValue;

    /** Derived effective unit price; ignored on inbound writes (R5.2, R5.3). */
    private BigDecimal unitPrice;

    /** Set by the snapshot service; ignored on inbound writes (R4.7). */
    private boolean unpriced;
}
