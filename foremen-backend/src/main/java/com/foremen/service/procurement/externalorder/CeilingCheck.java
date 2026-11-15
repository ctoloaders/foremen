package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;

/** FOR-11-03 (R12.4): the sale net unit price is the ceiling of the purchase net unit price. */
public final class CeilingCheck {

    private CeilingCheck() {
    }

    /** True when the purchase price is strictly above the sale price; a missing sale price never exceeds. */
    public static boolean exceeds(BigDecimal unitPurchaseNet, BigDecimal salePrice) {
        if (unitPurchaseNet == null || salePrice == null) {
            return false;
        }
        return unitPurchaseNet.compareTo(salePrice) > 0;
    }
}
