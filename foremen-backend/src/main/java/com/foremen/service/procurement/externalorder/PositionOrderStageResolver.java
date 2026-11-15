package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.foremen.dao.model.PositionStatus;

/**
 * FOR-11-03 (R17.2): decides the next position stage from its committed external-order coverage.
 * One step at a time; the caller re-resolves until the status is stable.
 *
 * <ul>
 *   <li>{@code WITH_BUYER -> ORDERED} when coverage reaches the quantity;</li>
 *   <li>{@code ORDERED -> SUPPLIER_PAID} when covered and every covering order is {@code PAID};</li>
 *   <li>{@code SUPPLIER_PAID -> ORDERED} when a covering order is no longer {@code PAID};</li>
 *   <li>{@code ORDERED | SUPPLIER_PAID -> WITH_BUYER} when coverage drops below the quantity;</li>
 *   <li>any other status: unchanged (IN_DELIVERY and later belong to FOR-11-04/05).</li>
 * </ul>
 */
public final class PositionOrderStageResolver {

    private PositionOrderStageResolver() {
    }

    /**
     * @param coveringOrderStatuses statuses of the orders that hold the position's committed lines
     */
    public static PositionStatus resolve(PositionStatus status, BigDecimal quantity, BigDecimal coverage,
                                         List<ExternalOrderStatus> coveringOrderStatuses) {
        if (status != PositionStatus.WITH_BUYER && status != PositionStatus.ORDERED
                && status != PositionStatus.SUPPLIER_PAID) {
            return status;
        }
        boolean covered = isCovered(quantity, coverage);
        boolean allPaid = coveringOrderStatuses != null && !coveringOrderStatuses.isEmpty()
                && coveringOrderStatuses.stream().allMatch(s -> s == ExternalOrderStatus.PAID);
        if (!covered) {
            return PositionStatus.WITH_BUYER;
        }
        return switch (status) {
            case WITH_BUYER -> PositionStatus.ORDERED;
            case ORDERED, SUPPLIER_PAID -> allPaid ? PositionStatus.SUPPLIER_PAID : PositionStatus.ORDERED;
            default -> status;
        };
    }

    private static boolean isCovered(BigDecimal quantity, BigDecimal coverage) {
        if (quantity == null || coverage == null || quantity.signum() <= 0) {
            return false;
        }
        return coverage.setScale(3, RoundingMode.HALF_UP).compareTo(quantity.setScale(3, RoundingMode.HALF_UP)) >= 0;
    }
}
