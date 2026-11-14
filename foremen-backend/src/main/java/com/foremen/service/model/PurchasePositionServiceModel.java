package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-11-00 service model of a purchase position. Carries the raw Money_Fields; the price-visibility
 * policy is applied by the controller mapper, never here.
 */
@Data
@NoArgsConstructor
public class PurchasePositionServiceModel {

    private Long id;
    private Long projectId;
    private String projectName;
    private PositionKind kind;
    private SourcingPath sourcingPath;
    private PositionStatus status;
    /** Seeded {@code delivery_statuses.code} of the stage (see {@code PositionStage}). */
    private String statusCode;
    private Long plannedMaterialId;
    private Long actualMaterialId;
    private Long deliveryCategoryId;
    private Integer blockNo;
    private BigDecimal quantity;
    private Long unitId;
    private BigDecimal orderedQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal plannedPurchasePrice;
    private BigDecimal salePrice;
    private BigDecimal actualPurchasePrice;
    private Long purchaseVatRateId;
    private Long saleVatRateId;
    private BigDecimal deliveryCostShare;
    private LocalDate needByDate;
    private LocalDate orderByDate;
    private LocalDate decisionDeadline;
    private LocalDate plannedDeliveryDate;
    private Long purchaseRequestId;
    private Long estimateReferenceId;
    /** Manual work-category override (FOR-11-01 R5.4); null = automatic. */
    private Long workCategoryId;
    private String notes;
    /** Number of retired options in the variant history; filled in batch by the service (R21.2). */
    private Integer variantCount;
}
