package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record WorkItemUpdateRequest(
    @NotNull Long workCategoryId,
    @NotNull Long unitId,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active,
    @PositiveOrZero BigDecimal internalPrice
) {}
