package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MaterialSellerUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active,
    @Size(max = 255) String website
) {}
