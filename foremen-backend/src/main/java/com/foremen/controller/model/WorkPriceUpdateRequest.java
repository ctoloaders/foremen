package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WorkPriceUpdateRequest(
    @NotNull Long workItemId,
    @NotNull Long currencyId,
    @NotNull @Positive BigDecimal netPrice,
    @NotNull LocalDate validFrom,
    LocalDate validTo
) {}
