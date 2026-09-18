package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record MaterialTypeUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
