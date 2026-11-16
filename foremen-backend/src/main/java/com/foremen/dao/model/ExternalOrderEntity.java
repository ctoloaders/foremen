package com.foremen.dao.model;

import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** FOR-11-03 (R11.1): a supplier-side order the buyer places and the financier pays ({@code external_orders}). */
@Entity
@Table(name = "external_orders")
@Getter
@Setter
@NoArgsConstructor
public class ExternalOrderEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_id")
    private PurchaseRequestEntity purchaseRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private MaterialSellerEntity seller;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExternalOrderStatus status = ExternalOrderStatus.DRAFT;

    /** The seller's own order number. */
    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(name = "order_link", length = 1000)
    private String orderLink;

    /** FOR-11-06 (R9.1): the supplier's online payment page; never exposed to non-money roles. */
    @Column(name = "payment_link", length = 1000)
    private String paymentLink;

    @Column(name = "delivery_cost_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal deliveryCostNet = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_vat_rate_id")
    private VatRateEntity deliveryVatRate;

    @Column(name = "expected_ship_date")
    private LocalDate expectedShipDate;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_buyer_id")
    private UserEntity assignedBuyer;

    @Column(length = 2000)
    private String note;

    @Column(name = "return_reason", length = 500)
    private String returnReason;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_source", length = 16)
    private PaidSource paidSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by")
    private UserEntity paidBy;

    @Column(name = "paid_note", length = 500)
    private String paidNote;
}
