package com.foremen.service.procurement.approval;

import java.math.BigDecimal;

/** FOR-11-02 (design 4): option projection for the staff view; carries the internal purchase price. */
public record StaffOptionSource(Long optionId, Long roundId, Integer sortOrder, BigDecimal unitSalePrice,
                                BigDecimal internalUnitPurchasePrice,
                                Long materialId, String model, String sku,
                                String producerNameRu, String producerNamePl, String link,
                                String sellerNameRu, String sellerNamePl) {
}
