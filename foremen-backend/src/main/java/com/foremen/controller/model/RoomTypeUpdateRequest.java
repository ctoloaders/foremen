package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record RoomTypeUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
