package com.foremen.service.procurement.planner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Source;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * One row of the purchase planner (FOR-11-01 R1.6). {@code money} is {@code null} for every audience
 * except {@code STAFF_FULL} (R4.4, R9.2). The last four fields are the FOR-11-03 additions (R18.4); they are
 * {@code null} for every audience except {@code STAFF_FULL}. Names are resolved to the caller's locale (R1.8).
 */
public record PlannerRowDto(
        Long positionId,
        Long projectId,
        String projectName,
        PositionKind kind,
        SourcingPath sourcingPath,
        PositionStatus status,
        String statusCode,
        Material material,
        BigDecimal quantity,
        String unitName,
        BigDecimal orderedQuantity,
        BigDecimal acceptedQuantity,
        Integer blockNo,
        String deliveryCategoryName,
        LocalDate needByDate,
        LocalDate orderByDate,
        LocalDate decisionDeadline,
        LocalDate plannedDeliveryDate,
        LocalDate siteDeliveryDate,
        Long requestId,
        String requestReferenceNumber,
        String assignedBuyerName,
        Long workCategoryId,
        Long effectiveWorkCategoryId,
        String effectiveWorkCategoryName,
        Source workCategorySource,
        List<WorkCategoryCandidate> workCategoryCandidates,
        LocalDate workStartDate,
        LocalDate suggestedNeedByDate,
        boolean late,
        boolean urgent,
        boolean deliveryRisk,
        boolean hasVariants,
        int variantCount,
        Money money,
        List<Action> allowedActions,
        Long proformaId,
        String proformaNumber,
        ProformaStatus proformaStatus,
        Integer externalOrderCount) {

    /** Effective material label: {@code COALESCE(actualMaterial, plannedMaterial)} plus the planned id. */
    public record Material(Long plannedMaterialId, Long actualMaterialId, String model, String sku,
                           String producerName, Long sellerId, String sellerName, String link,
                           String photo) {
    }

    /** A scheduled category that also matches the material but lost the earliest-start tie (R5.3). */
    public record WorkCategoryCandidate(Long id, String name, LocalDate startDate) {
    }

    /** The Money block (R4.4). */
    public record Money(BigDecimal salePrice, BigDecimal plannedPurchasePrice,
                        BigDecimal actualPurchasePrice, Long purchaseVatRateId, Long saleVatRateId,
                        BigDecimal deliveryCostShare, BigDecimal saleNetTotal, BigDecimal saleGrossTotal,
                        BigDecimal costNetTotal, BigDecimal marginAmount, BigDecimal marginPct,
                        boolean overCeiling) {
    }

    /** Manual actions the UI may offer for the caller and the row (R1.7). */
    public enum Action {
        EDIT, DELETE, SEND_TO_BUYER, MARK_CLIENT_OWN_IN_DELIVERY, CANCEL, REOPEN, REPLACE_VARIANT
    }
}
