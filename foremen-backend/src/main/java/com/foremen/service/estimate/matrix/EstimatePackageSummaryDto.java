package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;

/**
 * The package state and split totals of the Estimate tab header (FOR-05-04 additional tasks).
 * {@code applied == false} means the price-list calculation only; the amounts are then {@code null}.
 * With a package the net total is {@code packageSubtotal + extrasSubtotal} where
 * {@code packageSubtotal = zlM2 x areaM2}. Mirrored by the frontend {@code EstimatePackageSummary}.
 *
 * @param applied         the project's {@code packageApplied} flag
 * @param offerPackageId  the applied offer package id, or {@code null}
 * @param packageCode     the applied offer package code, or {@code null}
 * @param zlM2            the package price per m2 (net)
 * @param areaM2          the apartment area (sum of room floor areas)
 * @param packageSubtotal {@code zlM2 x areaM2}
 * @param extrasSubtotal  the net of works beyond the package at the price list
 * @param total           {@code packageSubtotal + extrasSubtotal}
 */
public record EstimatePackageSummaryDto(
        boolean applied,
        Long offerPackageId,
        String packageCode,
        BigDecimal zlM2,
        BigDecimal areaM2,
        BigDecimal packageSubtotal,
        BigDecimal extrasSubtotal,
        BigDecimal total) {

    /** The "no package applied" summary. */
    public static final EstimatePackageSummaryDto NONE =
            new EstimatePackageSummaryDto(false, null, null, null, null, null, null, null);
}
