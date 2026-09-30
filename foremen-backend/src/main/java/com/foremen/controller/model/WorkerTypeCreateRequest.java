package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * FOR-05-06 — WorkerType create request. Request-level bean validation gives a fast localized 400 for
 * blank {@code code}/{@code name} and a negative {@code tierPct}; the service-layer
 * {@code validateCreate} then enforces the cross-row rules (unique code, base share in {@code (0,1]},
 * exactly one base tier).
 */
public record WorkerTypeCreateRequest(
    @NotBlank String code,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull @DecimalMin(value = "0", message = "{error.worker.type.tierPct.range}") BigDecimal tierPct,
    Boolean base,
    Integer orderNo,
    Boolean active
) {}
