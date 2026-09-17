package com.foremen.controller.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record WorkPriceDtoExtendedModel(
    @NotNull Long workItemId,
    @NotEmpty List<@Valid PackagePriceUpsert> packagePrices
) {}
