package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

public record WorkPackageOverrideDtoExtendedModel(
    @NotNull Long workItemId,
    @NotNull Long offerPackageId,
    @NotNull Boolean member,
    String overrideSourceText
) {}
