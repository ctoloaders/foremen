package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-11-03 (R11.2, R12): one external-order line. On write the client sends {@code positionId},
 * {@code quantity}, {@code unitPurchaseNet}, optional {@code purchaseVatRateId} and
 * {@code aboveCeilingAcknowledged}; everything else is output only. Carries purchase prices: it is
 * returned only to money-privileged roles.
 */
@Data
@NoArgsConstructor
public class ExternalOrderLineServiceModel {

    private Long id;
    private Long positionId;
    private BigDecimal quantity;
    private BigDecimal unitPurchaseNet;
    private Long purchaseVatRateId;
    private boolean aboveCeilingAcknowledged;

    // --- derived, output only ---
    private BigDecimal purchaseVatRatePercent;
    private BigDecimal net;
    private BigDecimal vat;
    private BigDecimal gross;
    private BigDecimal deliveryCostShare;
    /** The position's sale net unit price (the ceiling). */
    private BigDecimal salePrice;
    private BigDecimal positionQuantity;
    /** Position quantity not covered by lines of OTHER non-cancelled orders (what this line may take). */
    private BigDecimal remainingQuantity;
    private boolean aboveCeiling;
}
