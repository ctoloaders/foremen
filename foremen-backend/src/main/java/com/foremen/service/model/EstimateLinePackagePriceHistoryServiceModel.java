package com.foremen.service.model;

import com.foremen.dao.model.DiscountKind;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only service model for an {@code EstimateLinePackagePriceHistory} row — the append-only
 * change-capture snapshot of a per-package project price (FOR-05-03, Requirement 6).
 *
 * <p>There is intentionally no {@code ...ServiceExtendedModel} counterpart: history is append-only
 * and never created/updated through a client write path (R6.1, R6.3). It carries the
 * {@code packagePriceId} owner reference plus the snapshotted price/discount values and the domain
 * change-capture fields ({@code changedBy}, {@code changedAt}), distinct from the {@code BaseEntity}
 * audit columns.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLinePackagePriceHistoryServiceModel {
    private Long id;
    private Long packagePriceId;

    /** Snapshot of the original (copied) unit price at capture time. */
    private BigDecimal originalUnitPrice;

    /** Snapshot of the discount kind at capture time. */
    private DiscountKind discountKind;

    /** Snapshot of the discount value at capture time. */
    private BigDecimal discountValue;

    /** Snapshot of the effective unit price at capture time. */
    private BigDecimal unitPrice;

    /** Who made the change (domain field, distinct from the audit {@code createdBy}). */
    private String changedBy;

    /** When the change was captured (domain field, distinct from the audit {@code createdDate}). */
    private LocalDateTime changedAt;
}
