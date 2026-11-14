package com.foremen.controller.model.staff;

import java.math.BigDecimal;

/**
 * FOR-11-02 (R4.5): option of a round as seen by staff. Money is net PLN, scale 2; {@code marginPercent}
 * is a percentage of the sale price (scale 2). Margin fields are {@code null} when unavailable.
 */
public record StaffOptionDto(Long optionId, Long materialId, String model, String sku, String producerName,
                             String link, BigDecimal unitSalePrice, BigDecimal internalUnitPurchasePrice,
                             BigDecimal marginPerUnit, BigDecimal marginPercent, BigDecimal lineMargin,
                             String sellerName) {
}
