package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record RoomTypeCreateRequest(
    @NotBlank String code,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
