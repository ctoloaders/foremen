package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WorkVolumeFormulaUpdateRequest(
    @NotNull Long workItemId,
    @NotBlank String sourceText
) {}
