package com.foremen.controller.model;

public record WorkCategoryCreateResponse(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                         boolean active) {}
