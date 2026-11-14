package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;

import jakarta.validation.constraints.Size;

/**
 * FOR-11-00 (R9.5): update request. Every field is optional (null = leave unchanged). The
 * system-owned fields ({@code status}, {@code orderedQuantity}, {@code acceptedQuantity},
 * {@code deliveryCostShare}, {@code plannedDeliveryDate}) and the immutable {@code projectId} /
 * {@code kind} are declared only so the service can reject a supplied value with 400
 * {@code error.purchasePosition.field.readOnly}.
 */
public record PurchasePositionUpdateRequest(
        Long projectId,
        PositionKind kind,
        SourcingPath sourcingPath,
        PositionStatus status,
        Long plannedMaterialId,
        Long actualMaterialId,
        Long deliveryCategoryId,
        Integer blockNo,
        BigDecimal quantity,
        Long unitId,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        BigDecimal plannedPurchasePrice,
        BigDecimal salePrice,
        BigDecimal actualPurchasePrice,
        Long purchaseVatRateId,
        Long saleVatRateId,
        BigDecimal deliveryCostShare,
        LocalDate needByDate,
        LocalDate orderByDate,
        LocalDate decisionDeadline,
        LocalDate plannedDeliveryDate,
        Long estimateReferenceId,
        @Size(max = 2000) String notes) {
}
