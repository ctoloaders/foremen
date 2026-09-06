package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

public record CurrencyUpdateRequest(
    @NotBlank String symbol,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
