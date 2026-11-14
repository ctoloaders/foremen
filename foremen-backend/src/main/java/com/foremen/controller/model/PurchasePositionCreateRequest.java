package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.SourcingPath;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * FOR-11-00 (R9.4): create request. System-owned fields ({@code status}, {@code orderedQuantity},
 * {@code acceptedQuantity}, {@code deliveryCostShare}, {@code plannedDeliveryDate},
 * {@code purchaseRequestId}) are deliberately absent. {@code unitId} and the VAT rates default from
 * the material and the VAT dictionary when omitted; semantic checks (quantity, prices, project) are
 * done by the service with the {@code error.purchasePosition.*} keys.
 */
public record PurchasePositionCreateRequest(
        @NotNull Long projectId,
        @NotNull PositionKind kind,
        @NotNull SourcingPath sourcingPath,
        @NotNull Long plannedMaterialId,
        Long deliveryCategoryId,
        Integer blockNo,
        @NotNull BigDecimal quantity,
        Long unitId,
        BigDecimal plannedPurchasePrice,
        BigDecimal salePrice,
        Long purchaseVatRateId,
        Long saleVatRateId,
        LocalDate needByDate,
        LocalDate orderByDate,
        LocalDate decisionDeadline,
        Long estimateReferenceId,
        Long workCategoryId,
        @Size(max = 2000) String notes) {
}
