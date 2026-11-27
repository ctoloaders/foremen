package com.foremen.controller.model;

import com.foremen.service.model.AiInstructions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkCategoryUpdateRequest(
    @NotNull Integer orderNo,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active,
    @Size(max = AiInstructions.MAX_LENGTH) String aiInstructions
) {

    public WorkCategoryUpdateRequest {
        aiInstructions = AiInstructions.normalize(aiInstructions);
    }
}
