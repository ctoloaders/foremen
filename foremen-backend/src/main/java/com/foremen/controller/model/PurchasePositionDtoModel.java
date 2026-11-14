package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;

/**
 * FOR-11-00 purchase position DTO, used for list, detail, create and update responses. The six
 * Money_Fields ({@code plannedPurchasePrice}, {@code actualPurchasePrice}, {@code purchaseVatRateId},
 * {@code salePrice}, {@code saleVatRateId}, {@code deliveryCostShare}) are {@code null} unless
 * {@code PositionPriceVisibility} lets the caller's audience see them (R10).
 */
public record PurchasePositionDtoModel(
        Long id,
        Long projectId,
        String projectName,
        PositionKind kind,
        SourcingPath sourcingPath,
        PositionStatus status,
        String statusCode,
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
        Long purchaseRequestId,
        Long estimateReferenceId,
        String notes,
        int variantCount,
        boolean hasVariants) {
}
