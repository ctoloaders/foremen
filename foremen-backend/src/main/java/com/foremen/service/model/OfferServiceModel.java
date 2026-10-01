package com.foremen.service.model;

import java.math.BigDecimal;

import com.foremen.dao.model.OfferStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-path service model for an {@code Offer} (FOR-05-07, Requirement 1), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code OfferService}
 * implements.
 *
 * <p>Following the FOR-04/FOR-05-03 operational-entity convention ({@code EstimateServiceModel}): flat
 * FK ids paired with resolved reference facts ({@code selectedPackageCode}), the {@code status} enum,
 * the monotonic {@code revision}, the recorded {@code approvedRevision}, and the derived
 * {@code totalNet}/{@code totalVat}/{@code totalGross} totals (read-only — they are recomputed from
 * the live-referenced estimate and never hand-entered, R1.3/R19.4).
 *
 * <p>The rich client/executor projections ({@code ClientOfferReadModel} / {@code ExecutorOfferReadModel})
 * are separate DTOs owned by the read-model tasks (2.3/9.x); this flat model exists only to back the
 * inherited CRUD surface.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferServiceModel {
    private Long id;
    private Long projectId;
    private Long estimateId;
    private Long selectedPackageId;
    private String selectedPackageCode;
    private Integer revision;
    private OfferStatus status;
    private Integer approvedRevision;

    /** Derived net total = referenced estimate net − Σ effective discounts (R1.3); read-only. */
    private BigDecimal totalNet;

    /** Derived VAT total from the discounted net (R1.3); read-only. */
    private BigDecimal totalVat;

    /** Derived gross total = totalNet + totalVat (R1.3); read-only. */
    private BigDecimal totalGross;
}
