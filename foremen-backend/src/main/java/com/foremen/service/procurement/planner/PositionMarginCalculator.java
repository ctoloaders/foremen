package com.foremen.service.procurement.planner;

import java.math.BigDecimal;

import com.foremen.service.estimate.margins.Margin;
import com.foremen.service.estimate.margins.MarginCostService;

/**
 * Pure per-position margin (FOR-11-01, Requirement 4.2).
 *
 * <p>Revenue is the sale line net; cost is {@code round2(unitPurchaseNet × qty) + deliveryCostShare},
 * where the unit purchase price is the actual price when set, otherwise the planned one. The margin
 * itself comes from {@link MarginCostService#materialMargin}.
 */
public final class PositionMarginCalculator {

    private PositionMarginCalculator() {
    }

    /**
     * @param saleUnitNet       sale unit price net; {@code null} ⇒ unavailable
     * @param actualUnit        actual purchase unit price net, preferred when set
     * @param plannedUnit       planned purchase unit price net, used when {@code actualUnit} is {@code null}
     * @param quantity          position quantity; {@code null} ⇒ unavailable
     * @param deliveryCostShare allocated delivery cost; {@code null} counts as zero
     * @return the margin, or {@link Margin#UNAVAILABLE} when revenue or both purchase prices are missing;
     *         the percentage is {@code null} for zero revenue
     */
    public static Margin margin(BigDecimal saleUnitNet, BigDecimal actualUnit, BigDecimal plannedUnit,
                                BigDecimal quantity, BigDecimal deliveryCostShare) {
        PositionMoneyMath.Line sale = PositionMoneyMath.saleLine(saleUnitNet, quantity, null);
        BigDecimal purchaseUnit = actualUnit != null ? actualUnit : plannedUnit;
        BigDecimal purchaseNet = PositionMoneyMath.purchaseNet(purchaseUnit, quantity);
        if (sale == null || purchaseNet == null) {
            return Margin.UNAVAILABLE;
        }
        BigDecimal cost = deliveryCostShare == null ? purchaseNet : purchaseNet.add(deliveryCostShare);
        return MarginCostService.materialMargin(sale.net(), cost);
    }
}
