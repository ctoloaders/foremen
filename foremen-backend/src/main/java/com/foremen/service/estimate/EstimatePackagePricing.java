package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Pure package pricing formula (FOR-05-04 additional tasks): with an applied offer package the
 * estimate net total is
 *
 * <pre>
 *   packageSubtotal = round2( packageZlM2 x apartmentAreaM2 )
 *   extrasSubtotal  = round2( sum of the net of works the package does not cover, at the price list )
 *   total           = packageSubtotal + extrasSubtotal
 * </pre>
 *
 * <p>Total, deterministic and side-effect free: a {@code null} or negative input contributes 0.
 * Without an applied package the caller does not use this class (price-list calculation only).
 */
public final class EstimatePackagePricing {

    private static final int SCALE = 2;

    private EstimatePackagePricing() {
    }

    /**
     * The package pricing breakdown, each amount rounded to 2 decimals.
     *
     * @param zlM2            the package price per m2 (net)
     * @param areaM2          the apartment area in m2
     * @param packageSubtotal {@code zlM2 x areaM2}
     * @param extrasSubtotal  the net of works beyond the package at the price list
     * @param total           {@code packageSubtotal + extrasSubtotal}
     */
    public record Result(
            BigDecimal zlM2,
            BigDecimal areaM2,
            BigDecimal packageSubtotal,
            BigDecimal extrasSubtotal,
            BigDecimal total) {
    }

    /**
     * Computes the package pricing.
     *
     * @param zlM2           package price per m2; {@code null} or negative is treated as 0
     * @param areaM2         apartment area; {@code null} or negative is treated as 0
     * @param extraLineNets  the net of each work NOT covered by the package; {@code null} entries and
     *                       negative values are treated as 0; a {@code null} collection means no extras
     * @return the breakdown
     */
    public static Result compute(BigDecimal zlM2, BigDecimal areaM2, Collection<BigDecimal> extraLineNets) {
        BigDecimal price = nonNegative(zlM2);
        BigDecimal area = nonNegative(areaM2);
        BigDecimal packageSubtotal = round2(price.multiply(area));
        BigDecimal extras = BigDecimal.ZERO;
        if (extraLineNets != null) {
            for (BigDecimal net : extraLineNets) {
                extras = extras.add(nonNegative(net));
            }
        }
        BigDecimal extrasSubtotal = round2(extras);
        return new Result(
                round2(price), round2(area), packageSubtotal, extrasSubtotal,
                round2(packageSubtotal.add(extrasSubtotal)));
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
