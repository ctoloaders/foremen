package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.service.model.AiInstructions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record WorkItemCreateRequest(
    @NotNull Long workCategoryId,
    @NotNull Long unitId,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active,
    @PositiveOrZero BigDecimal internalPrice,
    @Size(max = AiInstructions.MAX_LENGTH) String aiInstructions
) {

    public WorkItemCreateRequest {
        aiInstructions = AiInstructions.normalize(aiInstructions);
    }
}
