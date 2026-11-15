package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;

import com.foremen.exception.ForemenApiException;

/**
 * FOR-11-03 (R12.3): per position, the sum of line quantities over all non-cancelled orders (including
 * the one being edited) must not exceed the position quantity. Compared exactly at 3 decimals.
 */
public final class ExternalOrderCoverageValidator {

    public static final String OVER_COVERAGE = "error.externalOrder.line.overCoverage";
    private static final int SCALE = 3;

    private ExternalOrderCoverageValidator() {
    }

    /**
     * @param positionQuantity the position quantity
     * @param otherLinesQty    quantity already covered by lines of other non-cancelled orders (null = 0)
     * @param thisLineQty      the quantity of the line being validated (null = 0)
     */
    public static boolean isWithin(BigDecimal positionQuantity, BigDecimal otherLinesQty, BigDecimal thisLineQty) {
        return scaled(positionQuantity).compareTo(scaled(otherLinesQty).add(scaled(thisLineQty))) >= 0;
    }

    /** Quantity that can still be added to the position (never negative), scale 3. */
    public static BigDecimal remaining(BigDecimal positionQuantity, BigDecimal otherLinesQty) {
        BigDecimal r = scaled(positionQuantity).subtract(scaled(otherLinesQty));
        return r.signum() < 0 ? scaled(null) : r;
    }

    /** @throws ForemenApiException 409 {@value #OVER_COVERAGE} */
    public static void check(BigDecimal positionQuantity, BigDecimal otherLinesQty, BigDecimal thisLineQty) {
        if (!isWithin(positionQuantity, otherLinesQty, thisLineQty)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, OVER_COVERAGE);
        }
    }

    private static BigDecimal scaled(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
