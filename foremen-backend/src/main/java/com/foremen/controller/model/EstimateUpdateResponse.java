package com.foremen.controller.model;

import com.foremen.dao.model.EstimateStatus;

import java.math.BigDecimal;

/** Outbound update response for {@code Estimate} (FOR-05-03, Requirement 1). */
public record EstimateUpdateResponse(
        Long id,
        Long projectId,
        Long currencyId,
        Long vatRateId,
        EstimateStatus status,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross
) {}
