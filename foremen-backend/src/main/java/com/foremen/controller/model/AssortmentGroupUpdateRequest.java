package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssortmentGroupUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull Integer sortOrder
) {}
