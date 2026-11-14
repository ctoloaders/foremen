package com.foremen.service.procurement.approval;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-11-02 (design 4): narrowed projection of a position for the client view. Only sale-side columns
 * are selected by {@code ApprovalQueryService}; there is no purchase price, VAT, delivery, seller,
 * request or date field here, so the client assembler cannot leak one by mistake.
 *
 * <p>The item is the position's current one (actual material when set, else planned).
 */
public record ClientPositionSource(Long positionId, Integer blockNo, PositionStatus status,
                                   SourcingPath sourcingPath, BigDecimal quantity,
                                   String unitNameRu, String unitNamePl,
                                   BigDecimal salePrice, BigDecimal saleVatRatePercent,
                                   LocalDate decisionDeadline,
                                   Long materialId, String model, String sku,
                                   String producerNameRu, String producerNamePl,
                                   String link, String photo) {
}
