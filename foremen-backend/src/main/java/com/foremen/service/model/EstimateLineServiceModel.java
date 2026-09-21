package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Read-path service model for an {@code EstimateLine} (FOR-05-03, Requirement 2).
 *
 * <p>Flat FK ids paired with their resolved reference names ({@code workItemName},
 * {@code unitName} — localized to the request locale by the service mapper). The {@code workPriceId}
 * is the PROVENANCE reference only (nullable) and never drives value (R2.4, R2.5).
 *
 * <p>{@code unitPrice} is the frozen snapshot (source of truth, R2.3). {@code quantity} and
 * {@code valueNet} are the <em>derived</em> columns (R2.6, R2.7): {@code quantity} = Σ room
 * quantities, {@code valueNet} = {@code unitPrice × quantity}. They are exposed read-only here and
 * ignored on inbound writes by the mapper (task 5.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLineServiceModel {
    private Long id;
    private Long estimateId;
    private Long workItemId;
    private String workItemName;

    /** Provenance reference only (nullable); never drives value (R2.4). */
    private Long workPriceId;
    private Long unitId;
    private String unitName;
    private Integer lineNo;
    private String comment;

    /** Frozen snapshot unit price — source of truth for the line value (R2.3). */
    private BigDecimal unitPrice;

    /** Derived = Σ room quantities (R2.6); read-only. */
    private BigDecimal quantity;

    /** Derived = unitPrice × quantity (R2.7); read-only. */
    private BigDecimal valueNet;
}
