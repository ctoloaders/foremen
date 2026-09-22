package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WorkPriceDtoExtendedModel(
    @NotNull Long workItemId,
    @NotNull Long currencyId,
    @NotNull @Positive BigDecimal netPrice
) {}
