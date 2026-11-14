package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** FOR-11-00 (R11.1): internal purchase request grouping positions of one project for the buyer. */
@Entity
@Table(name = "purchase_requests")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseRequestEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_category_id")
    private DeliveryCategoryEntity deliveryCategory;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(name = "reference_link", length = 1000)
    private String referenceLink;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_buyer_id")
    private UserEntity assignedBuyer;

    @Column(length = 2000)
    private String notes;

    /** One-shot overdue notification marker; cleared when the expected date moves to today or later. */
    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

    /** Inverse side of {@link PurchasePositionEntity#getPurchaseRequest()}; the position owns the FK. */
    @OneToMany(mappedBy = "purchaseRequest", fetch = FetchType.LAZY)
    private List<PurchasePositionEntity> positions = new ArrayList<>();
}
