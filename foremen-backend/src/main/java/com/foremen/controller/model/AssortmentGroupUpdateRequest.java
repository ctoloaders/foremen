package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AssortmentGroupUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull Integer sortOrder,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal referenceQty,
    @NotNull @Pattern(regexp = "szt|m2") String referenceUnit
) {}
