package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * FOR-05-06 — WorkerType update request. {@code code} is immutable on update (mirrors the other
 * dictionaries), so it is not part of the payload.
 */
public record WorkerTypeUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull @DecimalMin(value = "0", message = "{error.worker.type.tierPct.range}") BigDecimal tierPct,
    Boolean base,
    Integer orderNo,
    Boolean active
) {}
