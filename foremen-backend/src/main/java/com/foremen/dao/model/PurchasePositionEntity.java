package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** FOR-11-00 (R7.1): the unit of control for finishing-material procurement ({@code purchase_positions}). */
@Entity
@Table(name = "purchase_positions")
@Getter
@Setter
@NoArgsConstructor
public class PurchasePositionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PositionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "sourcing_path", nullable = false, length = 16)
    private SourcingPath sourcingPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PositionStatus status = PositionStatus.PLANNED;

    /** FOR-11-06 (R5.3): null for an estimate-derived position whose material is not chosen yet. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planned_material_id")
    private FinishingMaterialEntity plannedMaterial;

    /** Null means the same item as {@link #plannedMaterial}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actual_material_id")
    private FinishingMaterialEntity actualMaterial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_category_id")
    private DeliveryCategoryEntity deliveryCategory;

    @Column(name = "block_no")
    private Integer blockNo;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private MeasurementUnitEntity unit;

    @Column(name = "ordered_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal orderedQuantity = BigDecimal.ZERO;

    @Column(name = "accepted_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Column(name = "planned_purchase_price", precision = 14, scale = 2)
    private BigDecimal plannedPurchasePrice;

    @Column(name = "sale_price", precision = 14, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "actual_purchase_price", precision = 14, scale = 2)
    private BigDecimal actualPurchasePrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_vat_rate_id")
    private VatRateEntity purchaseVatRate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_vat_rate_id")
    private VatRateEntity saleVatRate;

    @Column(name = "delivery_cost_share", nullable = false, precision = 14, scale = 2)
    private BigDecimal deliveryCostShare = BigDecimal.ZERO;

    @Column(name = "need_by_date")
    private LocalDate needByDate;

    @Column(name = "order_by_date")
    private LocalDate orderByDate;

    /** FOR-11-04 (R14): the order-by date for which the "order-by missed" notification was already sent. */
    @Column(name = "order_by_notified_for")
    private LocalDate orderByNotifiedFor;

    @Column(name = "decision_deadline")
    private LocalDate decisionDeadline;

    /** System-owned: synced from the request's expected delivery date. */
    @Column(name = "planned_delivery_date")
    private LocalDate plannedDeliveryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_id")
    private PurchaseRequestEntity purchaseRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_reference_id")
    private EstimateLineRoomMaterialEntity estimateReference;

    /** FOR-11-01 (R5.4): manual work-category override; null means "resolved automatically". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_category_id")
    private WorkCategoryEntity workCategory;
    @Column(length = 2000)
    private String notes;

    /** FOR-11-06 (design 6.1): stable key of the estimate row ("M:{materialId}" / "T:{finishingTypeId}"). */
    @Column(name = "estimate_source_key", length = 80)
    private String estimateSourceKey;

    @Column(name = "estimate_changed", nullable = false)
    private boolean estimateChanged;

    @Column(name = "estimate_removed", nullable = false)
    private boolean estimateRemoved;

    @Column(name = "added_by_buyer", nullable = false)
    private boolean addedByBuyer;

    @Column(name = "client_unpaid", nullable = false)
    private boolean clientUnpaid;
}
