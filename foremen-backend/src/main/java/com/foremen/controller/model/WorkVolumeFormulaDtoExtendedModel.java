package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WorkVolumeFormulaDtoExtendedModel(
    @NotNull Long workItemId,
    @NotBlank String sourceText
) {}
