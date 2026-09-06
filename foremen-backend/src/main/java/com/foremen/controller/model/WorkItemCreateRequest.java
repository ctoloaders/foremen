package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WorkItemCreateRequest(
    @NotNull Long workCategoryId,
    @NotNull Long unitId,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
