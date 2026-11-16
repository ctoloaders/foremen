package com.foremen.service.procurement.approval;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-11-02 (design 4): projection of a position for the staff view. Unlike {@link ClientPositionSource}
 * it carries purchase-side values; it must only be turned into a DTO by {@code StaffViewAssembler}
 * after the {@code STAFF_FULL} audience check. The item is the position's current one (actual material
 * when set, else planned).
 */
public record StaffPositionSource(Long positionId, Integer blockNo, PositionStatus status,
                                  SourcingPath sourcingPath, BigDecimal quantity,
                                  String unitNameRu, String unitNamePl,
                                  BigDecimal salePrice, BigDecimal plannedPurchasePrice,
                                  BigDecimal actualPurchasePrice, BigDecimal deliveryCostShare,
                                  LocalDate needByDate, LocalDate orderByDate, LocalDate plannedDeliveryDate,
                                  LocalDate decisionDeadline,
                                  Long requestId, String requestReference,
                                  Long materialId, String model, String sku,
                                  String producerNameRu, String producerNamePl, String link,
                                  String sellerNameRu, String sellerNamePl,
                                  String photo, String typeNameRu, String typeNamePl,
                                  boolean fromEstimate, boolean estimateChanged, boolean estimateRemoved,
                                  boolean clientUnpaid) {
}
