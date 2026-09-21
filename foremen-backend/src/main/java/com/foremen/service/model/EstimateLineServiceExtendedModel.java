package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Write-path service model for an {@code EstimateLine} (FOR-05-03, Requirement 2).
 *
 * <p>Mutable ({@code @Data}) carrying the persistable line fields: the {@code estimate},
 * {@code workItem} and {@code unit} FKs, the optional {@code workPrice} provenance FK, the
 * {@code lineNo}, {@code comment}, and the frozen {@code unitPrice} snapshot (R2.1–R2.4).
 *
 * <p>The derived {@code quantity}/{@code valueNet} are exposed for round-tripping but are ignored on
 * inbound create/update by the mapper (task 5.2) — they are computed by
 * {@code EstimateRecomputeService}, never hand-entered (R2.6, R2.7, R8.4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateLineServiceExtendedModel {
    private Long id;
    private Long estimateId;
    private Long workItemId;

    /** Provenance FK only (nullable); never drives value (R2.4). */
    private Long workPriceId;
    private Long unitId;
    private Integer lineNo;
    private String comment;

    /** Frozen snapshot unit price — source of truth (R2.3). */
    private BigDecimal unitPrice;

    /** Derived; ignored on inbound writes (R8.4). */
    private BigDecimal quantity;

    /** Derived; ignored on inbound writes (R8.4). */
    private BigDecimal valueNet;
}
