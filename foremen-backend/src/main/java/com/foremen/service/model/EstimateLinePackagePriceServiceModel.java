package com.foremen.service.model;

import com.foremen.dao.model.DiscountKind;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Read-path service model for an {@code EstimateLinePackagePrice} — the denormalized per-package
 * project price copied from the FOR-04-12b catalog at add-time (FOR-05-03, Requirements 4, 5).
 *
 * <p>Flat FK ids paired with the resolved {@code offerPackageName} (localized to the request
 * locale). {@code workPackagePriceId} is the PROVENANCE reference only (nullable) and never drives
 * value (R4.4, R4.5).
 *
 * <p>{@code originalUnitPrice} is the value copied at add-time (null when {@code unpriced}, R4.7).
 * The effective {@code unitPrice} is <em>derived</em> from
 * {@code (originalUnitPrice, discountKind, discountValue)} via {@code applyDiscount} (R5.2, R5.3);
 * it is exposed read-only here and ignored on inbound writes by the mapper (task 5.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLinePackagePriceServiceModel {
    private Long id;
    private Long lineId;
    private Long offerPackageId;
    private String offerPackageName;

    /** Provenance reference only (nullable); never drives value (R4.4). */
    private Long workPackagePriceId;

    /** Value copied from the catalog at add-time; null when unpriced (R4.3, R4.7). */
    private BigDecimal originalUnitPrice;

    /** Discount placeholder kind; null for the neutral (no-discount) state (R5.1). */
    private DiscountKind discountKind;

    /** Discount placeholder value; null for the neutral (no-discount) state (R5). */
    private BigDecimal discountValue;

    /** Derived effective unit price = applyDiscount(...) (R5.2, R5.3); read-only. */
    private BigDecimal unitPrice;

    /** True when the resolver returned empty at add-time and prices are null (R4.7). */
    private boolean unpriced;
}
