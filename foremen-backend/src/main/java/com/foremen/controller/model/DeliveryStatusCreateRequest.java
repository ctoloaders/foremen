package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeliveryStatusCreateRequest(
    @NotBlank String code,
    @NotNull Integer orderNo,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
