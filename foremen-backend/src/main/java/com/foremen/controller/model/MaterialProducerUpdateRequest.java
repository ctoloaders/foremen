package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record MaterialProducerUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
