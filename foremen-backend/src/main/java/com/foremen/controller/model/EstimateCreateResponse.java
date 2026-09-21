package com.foremen.controller.model;

import com.foremen.dao.model.EstimateStatus;

import java.math.BigDecimal;

/**
 * Outbound create response for {@code Estimate} (FOR-05-03, Requirement 1), including the derived
 * (all-zero for a freshly created, line-less estimate — R8.5) totals.
 */
public record EstimateCreateResponse(
        Long id,
        Long projectId,
        Long currencyId,
        Long vatRateId,
        EstimateStatus status,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross
) {}
