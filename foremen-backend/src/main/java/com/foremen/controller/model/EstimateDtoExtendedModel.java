package com.foremen.controller.model;

import com.foremen.dao.model.EstimateStatus;

import java.math.BigDecimal;

/**
 * Extended DTO for the single {@code Estimate} of a project (FOR-05-03, Requirement 1), mapped from
 * {@link com.foremen.service.model.EstimateServiceExtendedModel}. Exposes the flat FK ids and the
 * derived {@code totalNet}/{@code totalVat}/{@code totalGross} (read-only, R1.4, R8.4).
 */
public record EstimateDtoExtendedModel(
        Long id,
        Long projectId,
        Long currencyId,
        Long vatRateId,
        EstimateStatus status,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross
) {}
