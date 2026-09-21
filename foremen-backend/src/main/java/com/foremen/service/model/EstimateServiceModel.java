package com.foremen.service.model;

import com.foremen.dao.model.EstimateStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Read-path service model for the single {@code Estimate} of a project (FOR-05-03, Requirement 1).
 *
 * <p>Follows the FOR-04 operational-entity convention ({@code ProjectServiceModel},
 * {@code RoomServiceModel}): flat FK ids paired with their resolved reference names
 * ({@code projectName}, {@code currencyCode}, {@code vatRateName} — the latter localized to the
 * request locale by the service mapper), plus the {@code status} enum.
 *
 * <p>{@code totalNet}/{@code totalVat}/{@code totalGross} are the <em>derived</em> totals
 * ({@link com.foremen.dao.model.EstimateEntity}, R1.4, R8); they are exposed read-only here and are
 * ignored on inbound writes by the mapper (task 5.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimateServiceModel {
    private Long id;
    private Long projectId;
    private String projectName;
    private Long currencyId;
    private String currencyCode;
    private Long vatRateId;
    private String vatRateName;
    private EstimateStatus status;

    /** Derived net total = Σ line valueNet (R8.2); read-only. */
    private BigDecimal totalNet;

    /** Derived VAT total = round2(totalNet × vat) (R8.3); read-only. */
    private BigDecimal totalVat;

    /** Derived gross total = round2(totalNet + totalVat) (R8.3); read-only. */
    private BigDecimal totalGross;
}
