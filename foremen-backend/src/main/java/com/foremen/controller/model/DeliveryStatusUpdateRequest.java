package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeliveryStatusUpdateRequest(
    @NotNull Integer orderNo,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active
) {}
