package com.foremen.controller.model;

public record WorkItemCreateResponse(Long id, Long workCategoryId, Long unitId, String nameRU, String namePL,
                                     boolean active) {}
