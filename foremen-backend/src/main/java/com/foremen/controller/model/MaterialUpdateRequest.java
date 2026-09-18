package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record MaterialUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
