package com.foremen.service.procurement.approval;

import java.math.BigDecimal;

/** FOR-11-02 (design 4): narrowed projection of a round option; the internal purchase price is never selected. */
public record ClientOptionSource(Long optionId, Long roundId, Integer sortOrder, BigDecimal unitSalePrice,
                                 Long materialId, String model, String sku,
                                 String producerNameRu, String producerNamePl, String link, String photo) {
}
