package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * FOR-11-06 (R12.1): the delegation of a shipment's receipt to a project WORKER
 * ({@code goods_receipt_delegations}). At most one {@code ACTIVE} row per shipment (partial unique index).
 */
@Entity
@Table(name = "goods_receipt_delegations")
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceiptDelegationEntity extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private Long shipmentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_id", nullable = false, updatable = false)
    private UserEntity assignee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delegated_by", nullable = false, updatable = false)
    private UserEntity delegatedBy;

    @Column(name = "delegated_at", nullable = false)
    private OffsetDateTime delegatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DelegationStatus status = DelegationStatus.ACTIVE;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    private UserEntity revokedBy;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
