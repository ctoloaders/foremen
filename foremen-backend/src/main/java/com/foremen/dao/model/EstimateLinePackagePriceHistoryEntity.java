package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * EstimateLinePackagePriceHistory — the append-only change-capture skeleton for a per-package
 * project price (FOR-05-03, Requirement 6).
 *
 * <p>A row is written on every change to {@code originalUnitPrice}, discount, or the effective
 * {@code unitPrice} of the owning {@link EstimateLinePackagePriceEntity}; prior rows are never
 * updated or deleted (R6.1, R6.2, R6.3). {@code changedBy}/{@code changedAt} are the domain
 * change-capture fields, distinct from the {@link BaseEntity} audit columns.
 *
 * <p>Mirrors the {@code estimate_line_package_price_history} table (changeset 080).
 */
@Entity
@Table(name = "estimate_line_package_price_history")
@Getter
@Setter
@NoArgsConstructor
public class EstimateLinePackagePriceHistoryEntity extends BaseEntity {

    /** Owner FK ({@code ON DELETE CASCADE}): the per-package price this history row snapshots. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_price_id", nullable = false)
    private EstimateLinePackagePriceEntity packagePrice;

    /** Snapshot of the original (copied) unit price at capture time. */
    @Column(name = "original_unit_price")
    private BigDecimal originalUnitPrice;

    /** Snapshot of the discount kind at capture time. */
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_kind", length = 20)
    private DiscountKind discountKind;

    /** Snapshot of the discount value at capture time. */
    @Column(name = "discount_value")
    private BigDecimal discountValue;

    /** Snapshot of the effective unit price at capture time. */
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** Who made the change (domain field, distinct from the audit {@code createdBy}). */
    @Column(name = "changed_by")
    private String changedBy;

    /** When the change was captured (domain field, distinct from the audit {@code createdDate}). */
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
