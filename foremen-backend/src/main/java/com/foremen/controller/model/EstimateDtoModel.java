package com.foremen.controller.model;

import com.foremen.dao.model.EstimateStatus;

import java.math.BigDecimal;

/**
 * List/read DTO for the single {@code Estimate} of a project (FOR-05-03, Requirement 1). Mirrors
 * {@link com.foremen.service.model.EstimateServiceModel}: flat FK ids paired with their resolved
 * reference names ({@code projectName}, {@code currencyCode}, {@code vatRateName}), the
 * {@code status} enum, and the derived {@code totalNet}/{@code totalVat}/{@code totalGross}
 * (read-only — never accepted from a client payload, R1.4, R8.4).
 */
public record EstimateDtoModel(
        Long id,
        Long projectId,
        String projectName,
        Long currencyId,
        String currencyCode,
        Long vatRateId,
        String vatRateName,
        EstimateStatus status,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross
) {}
