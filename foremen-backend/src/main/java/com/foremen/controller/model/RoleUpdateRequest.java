package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record RoleUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    String descriptionRU,
    String descriptionPL
) {}
