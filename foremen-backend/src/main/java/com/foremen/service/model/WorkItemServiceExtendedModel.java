package com.foremen.service.model;

public record WorkItemServiceExtendedModel(Long id, Long workCategoryId, Long unitId, String nameRU, String namePL,
                                           boolean active) {
}
