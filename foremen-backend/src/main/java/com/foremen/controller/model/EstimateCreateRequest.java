package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * Inbound create request for {@code Estimate} (FOR-05-03, Requirement 1). {@code currencyId} and
 * {@code vatRateId} are optional — the service defaults {@code currencyId} to the seeded PLN
 * currency and {@code status} to {@code DRAFT} when omitted (R1.2, R1.3). The derived
 * {@code totalNet}/{@code totalVat}/{@code totalGross} are deliberately absent here: they are never
 * settable inputs (R1.4, R8.4).
 */
public record EstimateCreateRequest(
        @NotNull Long projectId,
        Long currencyId,
        Long vatRateId
) {}
