package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record RoleCreateRequest(
    @NotBlank String code,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    String descriptionRU,
    String descriptionPL,
    Boolean system
) {}
