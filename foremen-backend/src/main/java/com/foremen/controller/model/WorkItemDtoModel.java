package com.foremen.controller.model;

public record WorkItemDtoModel(Long id, Long workCategoryId, String workCategoryName, Long unitId, String unitName,
                               String name, boolean active) {}
