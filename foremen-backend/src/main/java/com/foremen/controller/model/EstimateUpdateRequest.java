package com.foremen.controller.model;

import com.foremen.dao.model.EstimateStatus;

/**
 * Inbound update request for {@code Estimate} (FOR-05-03, Requirement 1). Only the mutable
 * reference fields and lifecycle {@code status} are settable; the derived
 * {@code totalNet}/{@code totalVat}/{@code totalGross} are deliberately absent — they are never
 * settable inputs (R1.4, R8.4). {@code projectId} is not included since the owning project is fixed
 * at create time (the UNIQUE 1:1 invariant, R1.1, R1.6).
 */
public record EstimateUpdateRequest(
        Long currencyId,
        Long vatRateId,
        EstimateStatus status
) {}
