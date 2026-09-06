package com.foremen.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record VatRateCreateRequest(
    @NotBlank String code,
    @NotNull BigDecimal rate,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @JsonProperty("isDefault") Boolean isDefault,
    Boolean active
) {}
