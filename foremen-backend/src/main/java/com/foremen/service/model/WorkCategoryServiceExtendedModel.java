package com.foremen.service.model;

public record WorkCategoryServiceExtendedModel(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                               boolean active, String aiInstructions) {

    public WorkCategoryServiceExtendedModel {
        aiInstructions = AiInstructions.normalize(aiInstructions);
    }
}
