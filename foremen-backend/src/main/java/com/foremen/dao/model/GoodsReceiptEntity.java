package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * FOR-11-05 (R2.1): an append-only goods-receipt event for a position ({@code goods_receipts}, changeset 244).
 * Rows are never edited; a mistake is corrected by voiding ({@link #voidedAt} and friends).
 */
@Entity
@Table(name = "goods_receipts")
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceiptEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "position_id", nullable = false, updatable = false)
    private PurchasePositionEntity position;

    /** Shipment the event is linked to; {@code null} for unlinked events (over-delivery, returns of accepted units). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", updatable = false)
    private ShipmentEntity shipment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private ReceiptKind kind;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantity;

    /** RETURNED only: the units had been accepted earlier (takes accepted units back). */
    @Column(name = "from_accepted", nullable = false, updatable = false)
    private boolean fromAccepted;

    @Column(length = 2000, updatable = false)
    private String comment;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by", nullable = false, updatable = false)
    private UserEntity receivedBy;

    /** Client-generated idempotency key; rows of one split event share it and differ by {@link #partNo}. */
    @Column(name = "client_request_id", updatable = false)
    private UUID clientRequestId;

    @Column(name = "part_no", nullable = false, updatable = false)
    private short partNo;

    /** FOR-11-06 (R12.1, R12.6): the delegation under which a WORKER recorded the event; {@code null} otherwise. */
    @Column(name = "delegation_id", updatable = false)
    private Long delegationId;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voided_by")
    private UserEntity voidedBy;

    @Column(name = "void_reason", length = 500)
    private String voidReason;
}
