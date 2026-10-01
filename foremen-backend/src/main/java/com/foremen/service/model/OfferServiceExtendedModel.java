package com.foremen.service.model;

import java.math.BigDecimal;

import com.foremen.dao.model.OfferStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-path service model for an {@code Offer} (FOR-05-07, Requirement 1), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code OfferService}
 * implements.
 *
 * <p>Follows the FOR-05-03 {@code EstimateServiceExtendedModel} convention: mutable ({@code @Data})
 * flat FK ids plus the lifecycle fields. The derived {@code totalNet}/{@code totalVat}/{@code totalGross}
 * are exposed for round-tripping but ignored on inbound create/update by the mapper — they are
 * recomputed by {@code OfferTotalsCalculator} from the live-referenced estimate, never hand-entered
 * (R1.3, R19.4). The offer lifecycle itself (prepare/select-package/send/withdraw/approve) is driven
 * through {@code OfferService}'s dedicated methods, not raw CRUD writes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferServiceExtendedModel {
    private Long id;
    private Long projectId;
    private Long estimateId;
    private Long selectedPackageId;
    private Integer revision;
    private OfferStatus status;
    private Integer approvedRevision;

    /** Derived; ignored on inbound writes (R1.3/R19.4). */
    private BigDecimal totalNet;

    /** Derived; ignored on inbound writes (R1.3/R19.4). */
    private BigDecimal totalVat;

    /** Derived; ignored on inbound writes (R1.3/R19.4). */
    private BigDecimal totalGross;
}
