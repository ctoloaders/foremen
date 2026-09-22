package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

public record WorkPackageOverrideUpdateRequest(
    @NotNull Long workItemId,
    @NotNull Long offerPackageId,
    @NotNull Boolean member,
    String overrideSourceText
) {}
