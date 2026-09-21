package com.foremen.service.model;

import com.foremen.dao.model.EstimateStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Write-path service model for the single {@code Estimate} of a project (FOR-05-03, Requirement 1).
 *
 * <p>Follows the FOR-04 operational-entity convention ({@code RoomServiceExtendedModel}): mutable
 * ({@code @Data}) so the {@code EstimateService} can default {@code currency} to PLN and
 * {@code status} to {@link EstimateStatus#DRAFT} on create (R1.2, R1.3) before the entity is
 * persisted.
 *
 * <p>The derived {@code totalNet}/{@code totalVat}/{@code totalGross} are exposed for round-tripping
 * but are ignored on inbound create/update by the mapper (task 5.2) — they are computed by
 * {@code EstimateRecomputeService}, never hand-entered (R1.4, R8, R8.4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateServiceExtendedModel {
    private Long id;
    private Long projectId;
    private Long currencyId;
    private Long vatRateId;
    private EstimateStatus status;

    /** Derived; ignored on inbound writes (R8.4). */
    private BigDecimal totalNet;

    /** Derived; ignored on inbound writes (R8.4). */
    private BigDecimal totalVat;

    /** Derived; ignored on inbound writes (R8.4). */
    private BigDecimal totalGross;
}
