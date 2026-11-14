package com.foremen.service.procurement.position;

import java.math.BigDecimal;

/**
 * FOR-11-00 (R10): the six Money_Fields of a purchase position. VAT rates are carried as ids of the
 * referenced {@code vat_rates} rows.
 */
public record PositionMoney(
        BigDecimal plannedPurchasePrice,
        BigDecimal actualPurchasePrice,
        Long purchaseVatRateId,
        BigDecimal salePrice,
        Long saleVatRateId,
        BigDecimal deliveryCostShare) {

    public static final PositionMoney NONE = new PositionMoney(null, null, null, null, null, null);
}
