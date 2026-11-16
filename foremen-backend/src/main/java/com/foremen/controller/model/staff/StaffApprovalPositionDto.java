package com.foremen.controller.model.staff;

import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.approval.StaffAction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * FOR-11-02 (R4.5): position row of the staff view with internal values. {@code internalUnitPurchasePrice}
 * is the actual purchase unit price when set, otherwise the planned one. {@code marginPercent} is a
 * percentage of the sale line (scale 2); {@code lineMargin} includes the delivery cost share.
 * {@code clientUnpaid} (FOR-11-06 R10.4) marks a buyer-added position not yet paid by the client.
 * {@code openSuggestions} are open client suggestions; {@code history} lists closed rounds newest first.
 */
public record StaffApprovalPositionDto(Long positionId, Integer blockNo, Long materialId, String model,
                                       String sku, String producerName, BigDecimal quantity, String unitName,
                                       String statusCode, SourcingPath sourcingPath, BigDecimal salePrice,
                                       BigDecimal plannedPurchasePrice, BigDecimal internalUnitPurchasePrice,
                                       BigDecimal marginPerUnit, BigDecimal marginPercent, BigDecimal lineMargin,
                                       String sellerName, Long requestId, String requestReference,
                                       LocalDate needByDate, LocalDate orderByDate, LocalDate plannedDeliveryDate,
                                       LocalDate decisionDeadline, boolean overdue, StaffRoundDto openRound,
                                       List<StaffRoundDto> openSuggestions, List<StaffRoundDto> history,
                                       Set<StaffAction> allowedActions, String thumbnailUrl,
                                       String materialTypeName, boolean fromEstimate,
                                       boolean estimateChanged, boolean estimateRemoved,
                                       boolean clientUnpaid) {
}
